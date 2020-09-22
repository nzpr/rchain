package coop.rchain.casper

import cats.Applicative
import cats.data.EitherT
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.syntax.bracket
import cats.syntax.all._
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.casper.protocol._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util._
import coop.rchain.casper.util.ProtoUtil._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang._
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.catscontrib.BooleanF._
import coop.rchain.catscontrib.Catscontrib.ToBooleanF
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.metrics.implicits._
import coop.rchain.models.BlockHash._
import coop.rchain.models.{EquivocationRecord, NormalizerEnv}
import coop.rchain.models.Validator.Validator
import coop.rchain.shared._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.casperbuffer.CasperBufferStorage
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.blockstorage.finality.LastFinalizedStorage
import coop.rchain.casper.engine.BlockRetriever
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.signatures.Signed

class MultiParentCasperImpl[F[_]: Sync: Concurrent: Log: Time: SafetyOracle: LastFinalizedBlockCalculator: BlockStore: BlockDagStorage: LastFinalizedStorage: CommUtil: EventPublisher: Estimator: DeployStorage: BlockRetriever: BlockCreator](
    validatorId: Option[ValidatorIdentity],
    approvedBlock: BlockMessage,
    blockProcessingLock: Semaphore[F],
    blocksInProcessing: Ref[F, Set[BlockHash]]
)(
    implicit casperBuffer: CasperBufferStorage[F],
    metricsF: Metrics[F],
    spanF: Span[F],
    runtimeManager: RuntimeManager[F]
) extends MultiParentCasper[F] {
  import MultiParentCasper.MetricsSource
  import MultiParentCasperImpl._

  implicit private val logSource: LogSource = LogSource(this.getClass)
  private[this] val syncF                   = Sync[F]

  private[this] val AddBlockMetricsSource =
    Metrics.Source(CasperMetricsSource, "add-block")
  private[this] val CreateBlockMetricsSource =
    Metrics.Source(CasperMetricsSource, "create-block")

  val shardConfigMap = new ShardOnChainConfig

  //TODO rename to getApprovedBlock
  def getGenesis: F[BlockMessage] = approvedBlock.pure[F]

  def getVersion: F[Long] = 1L.pure[F]

  def getValidator: F[Option[PublicKey]] = validatorId.map(_.publicKey).pure[F]

  override def getBlocksInProcessing: F[Set[BlockHash]] = blocksInProcessing.get

  // Later this should replace addBlock, but for now one more method created, or there are too many tests to fix
  def addBlockFromStore(
      blockHash: BlockHash,
      allowAddFromBuffer: Boolean
  ): F[ValidBlockProcessing] =
    for {
      blockAvailable <- BlockStore[F].get(blockHash)
      returnNoBlockInStore = BlockStatus
        .exception(
          new Exception("Casper tries to add block that is not present in BlockStore")
        )
        .asLeft[ValidBlock]
        .pure[F]

      result <- blockAvailable match {
                 case None =>
                   returnNoBlockInStore
                 case Some(b) =>
                   for {
                     dag                   <- blockDag
                     missingDepCheckResult <- Validate.missingBlocks(b, dag)
                     dagMissingBlockDependencies = missingDepCheckResult equals Left(
                       InvalidBlock.MissingBlocks
                     )

                     blockIsReady <- ~^(dagMissingBlockDependencies.pure[F]) ||^
                                      isApprovedBlockChild(b)

                     r <- if (blockIsReady) addBlock(b, allowAddFromBuffer)
                         else fetchMissingDependencies(b) >> missingDepCheckResult.pure[F]

                   } yield r
               }
    } yield result

  private def isApprovedBlockChild(blockMessage: BlockMessage): F[Boolean] =
    blockMessage.header.parentsHashList.contains(approvedBlock.blockHash).pure[F]

  /**
    * Add block to Casper
    *
    * @param b                  Block
    * @param allowAddFromBuffer If block is in CaspeBuffer, try to add it instead of reporting already processed.
    *                           This is required for continuing syncing after node restart
    */
  def addBlock(b: BlockMessage, allowAddFromBuffer: Boolean): F[ValidBlockProcessing] = {

    def createNew: F[Unit] =
      for {
        _          <- Metrics[F].incrementCounter("propose")
        maybeBlock <- createBlock
        result <- maybeBlock match {
                   case _: NoBlock =>
                     BlockStatus
                       .exception(new Exception("Error while creating block"))
                       .asLeft[ValidBlock]
                       .pure[F]
                   case Created(block) =>
                     Log[F].info(s"Proposing ${PrettyPrinter.buildString(block)}") *>
                       addBlock(block)
                 }
      } yield ()

    def addNextReady: F[Unit] =
      for {
        _ <- createNew

        hash <- getNextReadyBlock
        _ <- Applicative[F].whenA(hash.isDefined)(
              Log[F].info(
                s"Next block ready for processing ${PrettyPrinter.buildString(hash.get)}."
              ) >>
                addBlockFromStore(hash.get, allowAddFromBuffer = true)
            )
      } yield ()

    for {
      // Save block in block store
      // This is required for some unit tests to pass
      // TODO remove this and rewrite failing tests
      _         <- BlockStore[F].contains(b.blockHash).ifM(().pure[F], BlockStore[F].put(b))
      shardConf <- shardConfigMap.get(ProtoUtil.postStateHash(b))
      // We force Casper to be single t hreaded. More investigations needed to allow it be multi threaded.
      addResult <- for {
                    hashInProcess <- blocksInProcessing.modify(
                                      cur => ((cur + b.blockHash), cur.contains(b.blockHash))
                                    )
                    _ <- Log[F].info(
                          s"Block ${PrettyPrinter.buildString(b, short = true)} start processing."
                        )
                    r <- if (!hashInProcess) addBlockInLock(b, allowAddFromBuffer, shardConf)
                        else BlockStatus.processed.asLeft[ValidBlock].pure[F]
                    _ <- BlockRetriever[F].ackInCasper(b.blockHash)
                  } yield r
      _ <- casperBuffer.remove(b.blockHash) >> blocksInProcessing.update(_ - b.blockHash)

      _ <- Log[F].info(
            s"Block ${PrettyPrinter.buildString(b, short = true)} processed."
          )
      _ <- addResult match {
            case Right(_) =>
              for {
                _ <- metricsF.setGauge("block-height", blockNumber(b))(AddBlockMetricsSource)
                _ <- CommUtil[F].sendBlockHash(b.blockHash, b.sender)
                _ <- addNextReady
              } yield ()
            case Left(InvalidBlock.AdmissibleEquivocation) =>
              for {
                _ <- CommUtil[F].sendBlockHash(b.blockHash, b.sender)
                _ <- addNextReady
              } yield ()
            case _ => ().pure[F]
          }
    } yield addResult
  }

  private def addBlockInLock(
      b: BlockMessage,
      allowAddFromBuffer: Boolean,
      shardConf: CasperShardConf
  ): F[ValidBlockProcessing] = {
    for {
      dag <- blockDag
      ignore <- ((!allowAddFromBuffer).pure[F] &&^ bufferContains(b.blockHash)) ||^
                 dagContains(b.blockHash)

      result <- if (ignore) {
                 Log[F]
                   .info(
                     s"Block ${PrettyPrinter.buildString(b, short = true)} " +
                       s"has already been processed."
                   )
                   .as(BlockStatus.processed.asLeft[ValidBlock])
               } else {
                 for {
                   validSender <- Validate.blockSenderHasWeight(b, approvedBlock, dag)
                   resultWithUpdDag <- if (!validSender)
                                        // TODO What's the scenario when this can happen?
                                        (BlockStatus.invalidSender.asLeft[ValidBlock], dag).pure
                                      else attemptAdd(b, dag, shardConf)
                   (result, updatedDag) = resultWithUpdDag
                   tipHashes            <- estimator(updatedDag)
                   _                    <- Span[F].mark("after-estimator")
                   tipHash              = tipHashes.head
                   _ <- Log[F].info(
                         s"New fork-choice tip is block ${PrettyPrinter.buildString(tipHash)}."
                       )
                 } yield result
               }
    } yield result
  }.timer("add-block-time")

  private def updateLastFinalizedBlock(
      newBlock: BlockMessage,
      finalizationRate: Int,
      faultToleranceThreshold: Float
  ): F[Unit] =
    Applicative[F].whenA(newBlock.body.state.blockNumber % finalizationRate == 0)(for {
      dag                    <- blockDag
      lastFinalizedBlockHash <- LastFinalizedStorage[F].get(approvedBlock)
      updatedLastFinalizedBlockHash <- LastFinalizedBlockCalculator[F]
                                        .run(dag, lastFinalizedBlockHash, faultToleranceThreshold)
      _ <- LastFinalizedStorage[F].put(updatedLastFinalizedBlockHash)
      _ <- EventPublisher[F]
            .publish(
              RChainEvent.blockFinalised(updatedLastFinalizedBlockHash.base16String)
            )
            .whenA(lastFinalizedBlockHash != updatedLastFinalizedBlockHash)
    } yield ())

  /**
    * Check if there are blocks in CasperBuffer available with all dependencies met.
    * @return First from the set of available blocks
    */
  private def getNextReadyBlock: F[Option[BlockHash]] = {
    import cats.instances.list._
    for {
      pendants       <- CasperBufferStorage[F].getPendants
      pendantsStored <- pendants.toList.filterA(BlockStore[F].contains)
      depFreePendants <- pendantsStored.filterA { pendant =>
                          for {
                            pendantBlock   <- BlockStore[F].get(pendant)
                            justifications = pendantBlock.get.justifications
                            // If even one of justifications is not in DAG - block is not dependency free
                            missingDep <- justifications
                                           .map(_.latestBlockHash)
                                           .existsM(dagContains(_).not)
                          } yield !missingDep
                        }
      enqueued <- BlockRetriever[F].getEnqueuedToCasper
      all      = depFreePendants // ++ enqueued
      _ <- Log[F].info(
            s"Blocks ready to be added: " +
              s"dependency free buffer pendants ${PrettyPrinter.buildString(depFreePendants)}, " +
              " " // s"enqueued to Casper ${PrettyPrinter.buildString(enqueued)}"
          )
    } yield all.headOption
  }

  def dagContains(hash: BlockHash): F[Boolean] = blockDag.flatMap(_.contains(hash))

  def bufferContains(hash: BlockHash): F[Boolean] = casperBuffer.contains(hash)

  def contains(hash: BlockHash): F[Boolean] = bufferContains(hash) ||^ dagContains(hash)

  def deploy(d: Signed[DeployData]): F[Either[DeployError, DeployId]] = {
    import cats.instances.either._
    import coop.rchain.models.rholang.implicits._

    InterpreterUtil
      .mkTerm(d.data.term, NormalizerEnv(d))
      .bitraverse(
        err => DeployError.parsingError(s"Error in parsing term: \n$err").pure[F],
        _ => addDeploy(d)
      )
  }

  def addDeploy(deploy: Signed[DeployData]): F[DeployId] =
    for {
      _ <- DeployStorage[F].add(List(deploy))
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield deploy.sig

  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    Estimator[F].tips(dag, approvedBlock)

  def createBlock: F[CreateBlockStatus] = spanF.trace(CreateBlockMetricsSource) {
    (validatorId match {
      case Some(validatorIdentity) =>
        for {
          dag <- BlockDagStorage[F].getRepresentation
          block <- BlockCreator[F]
                    .createBlock(
                      dag,
                      approvedBlock,
                      validatorIdentity,
                      runtimeManager,
                      shardConfigMap
                    )
          result <- block match {
                     case c: Created =>
                       spanF.mark("block-store-put") >>
                         BlockStore[F].put(c.block) >>
                         BlockRetriever[F].ackReceive(c.block.blockHash) >>
                         EventPublisher[F]
                           .publish(MultiParentCasperImpl.createdEvent(c))
                           .as[CreateBlockStatus](c)
                     case o: CreateBlockStatus => o.pure
                   }
        } yield result
      case None => CreateBlockStatus.readOnlyMode.pure
    }).timer("create-block-time")
  }

  def lastFinalizedBlock: F[BlockMessage] =
    for {
      lastFinalizedBlockHash <- LastFinalizedStorage[F].get(approvedBlock)
      blockMessage           <- BlockStore[F].getUnsafe(lastFinalizedBlockHash)
    } yield blockMessage

  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    BlockDagStorage[F].accessEquivocationsTracker { tracker =>
      tracker.equivocationRecords.map { equivocations =>
        equivocations
          .map(_.equivocator)
          .flatMap(weights.get)
          .sum
          .toFloat / weightMapTotal(weights)
      }
    }

  /*
   * TODO: Pass in blockDag. We should only call _blockDag.get at one location.
   * This would require returning the updated block DAG with the block status.
   *
   * We want to catch equivocations only after we confirm that the block completing
   * the equivocation is otherwise valid.
   */
  private def attemptAdd(
      b: BlockMessage,
      dag: BlockDagRepresentation[F],
      shardConf: CasperShardConf
  ): F[(ValidBlockProcessing, BlockDagRepresentation[F])] = {
    val validationStatus: EitherT[F, BlockError, ValidBlock] =
      for {
        _ <- EitherT(
              Validate.blockSummary(b, approvedBlock, dag, approvedBlock.shardId, deployLifespan)
            )
        _ <- EitherT.liftF(Span[F].mark("post-validation-block-summary"))
        _ <- EitherT(
              InterpreterUtil
                .validateBlockCheckpoint(b, dag, runtimeManager)
                .map {
                  case Left(ex)       => Left(ex)
                  case Right(Some(_)) => Right(BlockStatus.valid)
                  case Right(None) =>
                    val isOwnBlock = PublicKey(b.sender).some == validatorId.map(_.publicKey)
                    if (!isOwnBlock) Left(BlockStatus.invalidTransaction)
                    else
                      // Prevent validator to slash itself for invalid block
                      Left(
                        BlockStatus.exception(
                          new Exception("Validation of own block failed, adding block canceled.")
                        )
                      )
                }
            )
        _ <- EitherT.liftF(Span[F].mark("transactions-validated"))
        _ <- EitherT(Validate.bondsCache(b, runtimeManager))
        _ <- EitherT.liftF(Span[F].mark("bonds-cache-validated"))
        _ <- EitherT(Validate.neglectedInvalidBlock(b, dag))
        _ <- EitherT.liftF(Span[F].mark("neglected-invalid-block-validated"))
        _ <- EitherT(
              EquivocationDetector.checkNeglectedEquivocationsWithUpdate(b, dag, approvedBlock)
            )
        _      <- EitherT.liftF(Span[F].mark("neglected-equivocation-validated"))
        depDag <- EitherT.liftF(casperBuffer.toDoublyLinkedDag)
        status <- EitherT(EquivocationDetector.checkEquivocations(depDag, b, dag))
        _      <- EitherT.liftF(Span[F].mark("equivocation-validated"))
      } yield status

    for {
      _      <- Span[F].mark("attempt-add")
      _      <- Log[F].info(s"Attempting to add Block ${PrettyPrinter.buildString(b, true)} to DAG.")
      status <- validationStatus.value
      updatedDag <- addEffects(
                     status,
                     b,
                     dag,
                     shardConf.finalizationRate,
                     shardConf.faultToleranceThreshold
                   )
      _ <- Span[F].mark("effects-added")
    } yield (status, updatedDag)
  }

  // TODO: Handle slashing
  private def addEffects(
      blockProcessingResult: ValidBlockProcessing,
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      finalizationRate: Int,
      faultToleranceThreshold: Float
  ): F[BlockDagRepresentation[F]] =
    blockProcessingResult
      .map { _ =>
        // Add successful! Send block hash to peers, log success, try to add other blocks
        for {
          updatedDag <- BlockDagStorage[F].insert(block, invalid = false)
          _ <- Log[F].info(
                s"Added ${PrettyPrinter.buildString(block, true)}"
              )
          _ <- updateLastFinalizedBlock(block, finalizationRate, faultToleranceThreshold)
        } yield updatedDag
      }
      .leftMap {
        case InvalidBlock.MissingBlocks => dag.pure

        case InvalidBlock.AdmissibleEquivocation =>
          val baseEquivocationBlockSeqNum = block.seqNum - 1
          for {
            _ <- BlockDagStorage[F].accessEquivocationsTracker { tracker =>
                  for {
                    equivocations <- tracker.equivocationRecords
                    _ <- syncF.unlessA(equivocations.exists {
                          case EquivocationRecord(validator, seqNum, _) =>
                            block.sender == validator && baseEquivocationBlockSeqNum == seqNum
                          // More than 2 equivocating children from base equivocation block and base block has already been recorded
                        }) {
                          val newEquivocationRecord =
                            EquivocationRecord(
                              block.sender,
                              baseEquivocationBlockSeqNum,
                              Set.empty[BlockHash]
                            )
                          tracker.insertEquivocationRecord(newEquivocationRecord)
                        }
                  } yield ()
                }
            // We can only treat admissible equivocations as invalid blocks if
            // casper is single threaded.
            updatedDag <- handleInvalidBlockEffect(InvalidBlock.AdmissibleEquivocation, block)
          } yield updatedDag

        case InvalidBlock.IgnorableEquivocation =>
          /*
           * We don't have to include these blocks to the equivocation tracker because if any validator
           * will build off this side of the equivocation, we will get another attempt to add this block
           * through the admissible equivocations.
           */
          Log[F]
            .info(
              s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
            )
            .as(dag)

        case ib: InvalidBlock if InvalidBlock.isSlashable(ib) =>
          handleInvalidBlockEffect(ib, block)

        case ib: InvalidBlock =>
          Log[F]
            .warn(
              s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for $ib."
            )
            .as(dag)

        case BlockError.Processed =>
          syncF.raiseError[BlockDagRepresentation[F]](
            new RuntimeException(s"A block should not be processing at this stage.")
          )

        case BlockError.BlockException(ex) =>
          Log[F]
            .error(s"Encountered exception in while processing block ${PrettyPrinter
              .buildString(block.blockHash)}: ${ex.getMessage}")
            .as(dag)

      }
      .merge

  private def fetchMissingDependencies(
      b: BlockMessage
  ): F[Unit] = {
    import cats.instances.list._
    for {
      // Request blocks that are not in DAG, CasperBuffer
      allDeps      <- dependenciesHashesOf(b).pure[F]
      depsInDag    <- allDeps.filterA(dagContains)
      depsInBuffer <- allDeps.filterA(bufferContains)
      // In addition, we have to check equivocation tracker, as admissible equivocations are not stored in DAG
      equivocationHashes <- BlockDagStorage[F].accessEquivocationsTracker { tracker =>
                             tracker.equivocationRecords.map { equivocations =>
                               equivocations.flatMap(_.equivocationDetectedBlockHashes)
                             }
                           }
      depsInEqTracker = allDeps.filter(equivocationHashes.contains)

      missingDeps = allDeps filterNot (
          d => depsInDag.contains(d) || depsInBuffer.contains(d) || depsInEqTracker.contains(d)
      )
      _ <- (missingDeps ++ depsInBuffer).traverse(casperBuffer.addRelation(_, b.blockHash))
      _ <- BlockRetriever[F].ackInCasper(b.blockHash)
      _ <- Log[F].info(
            s"Block ${PrettyPrinter.buildString(b, short = true)} missing dependencies. " +
              s"Fetching: ${PrettyPrinter.buildString(missingDeps)}. " +
              s"Already in CasperBuffer: ${PrettyPrinter.buildString(depsInBuffer)}. " +
              s"Already in DAG: ${PrettyPrinter.buildString(depsInDag)}."
          )
      _ <- missingDeps.traverse_(
            BlockRetriever[F]
              .admitHash(_, admitHashReason = BlockRetriever.MissingDependencyRequested)
          )
    } yield ()
  }

  // TODO: Slash block for status except InvalidUnslashableBlock
  private def handleInvalidBlockEffect(
      status: BlockError,
      block: BlockMessage
  ): F[BlockDagRepresentation[F]] =
    Log[F].warn(
      s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
    ) >> BlockDagStorage[F].insert(block, invalid = true)

  def getRuntimeManager: F[RuntimeManager[F]] = syncF.pure(runtimeManager)

  def fetchDependencies: F[Unit] = {
    import cats.instances.list._
    for {
      pendants       <- casperBuffer.getPendants
      pendantsUnseen <- pendants.toList.filterA(BlockStore[F].contains(_).not)
      _ <- Log[F].debug(s"Requesting CasperBuffer pendant hashes, ${pendantsUnseen.size} items.") >>
            pendantsUnseen.toList.traverse_(
              dependency =>
                Log[F]
                  .debug(
                    s"Sending dependency ${PrettyPrinter.buildString(dependency)} to BlockRetriever"
                  ) >>
                  BlockRetriever[F].admitHash(
                    dependency,
                    admitHashReason = BlockRetriever.MissingDependencyRequested
                  )
            )
    } yield ()
  }
}

final class ShardOnChainConfig[F[_]: Concurrent](implicit val runtimeManager: RuntimeManager[F]) {

  val state: Ref[F, Map[StateHash, Deferred[F, CasperShardConf]]] =
    Ref.unsafe(Map.empty[StateHash, Deferred[F, CasperShardConf]])

  def get(stateHash: StateHash): F[CasperShardConf] =
    for {
      newEntry <- Deferred[F, CasperShardConf]
      r <- state.modify[(Deferred[F, (CasperShardConf)], Boolean)] { s =>
            val firstRequest = !s.contains(stateHash)
            if (firstRequest)
              (s + (stateHash -> newEntry), (newEntry, true))
            else
              (s, (s(stateHash), false))
          }
      (configDeferred, notComplete) = r
      _ <- Applicative[F].whenA(notComplete)(
            for {
              config <- runtimeManager.getShardConfig(stateHash)
              _      <- configDeferred.complete(config)
            } yield ()
          )
      config <- configDeferred.get
    } yield config
}

object MultiParentCasperImpl {

  // TODO: Extract hardcoded deployLifespan from shard config
  // Size of deploy safety range.
  // Validators will try to put deploy in a block only for next `deployLifespan` blocks.
  // Required to enable protection from re-submitting duplicate deploys
  val deployLifespan = 50

  def addedEvent(block: BlockMessage): RChainEvent = {
    val (blockHash, parents, justifications, deployIds, creator, seqNum) = blockEvent(block)
    RChainEvent.blockAdded(
      blockHash,
      parents,
      justifications,
      deployIds,
      creator,
      seqNum
    )
  }

  def createdEvent(cbs: Created): RChainEvent = {
    val (blockHash, parents, justifications, deployIds, creator, seqNum) = blockEvent(cbs.block)
    RChainEvent.blockCreated(
      blockHash,
      parents,
      justifications,
      deployIds,
      creator,
      seqNum
    )
  }

  private def blockEvent(block: BlockMessage) = {
    import cats.instances.list._
    import cats.instances.option._

    val blockHash = block.blockHash.base16String
    val parentHashes =
      block.header.parentsHashList.map(_.base16String)
    val justificationHashes =
      block.justifications.toList
        .map(j => (j.validator.base16String, j.latestBlockHash.base16String))
    val deployIds: List[String] =
      block.body.deploys.map(pd => PrettyPrinter.buildStringNoLimit(pd.deploy.sig))
    val creator = block.sender.base16String
    val seqNum  = block.seqNum
    (blockHash, parentHashes, justificationHashes, deployIds, creator, seqNum)
  }
}
