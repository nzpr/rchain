package coop.rchain.casper

import cats.data.{EitherT, OptionT}
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.casperbuffer.CasperBufferStorage
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.blockstorage.state.CasperStateValidated
import coop.rchain.casper.BlockStatus._
import coop.rchain.casper.engine.BlockRetriever
import coop.rchain.casper.finality.Finalizer
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.casper.protocol._
import coop.rchain.casper.state.CasperStateManager
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.ProtoUtil._
import coop.rchain.casper.util._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.casper.util.rholang._
import coop.rchain.catscontrib.Catscontrib.ToBooleanF
import coop.rchain.crypto.signatures.Signed
import coop.rchain.dag.DagOps
import coop.rchain.metrics.implicits._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash._
import coop.rchain.models.Validator.Validator
import coop.rchain.models.syntax._
import coop.rchain.models.{BlockHash => _, _}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.shared._

// format: off
class MultiParentCasperImpl[F[_]
  /* Execution */   : Concurrent: Time
  /* Transport */   : CommUtil: BlockRetriever: EventPublisher
  /* Rholang */     : RuntimeManager
  /* Casper */      : Estimator: SafetyOracle
  /* Storage */     : BlockStore: BlockDagStorage: DeployStorage: CasperBufferStorage
  /* Diagnostics */ : Log: Metrics: Span] // format: on
(
    validatorId: Option[ValidatorIdentity],
    // todo this should be read from chain, for now read from startup options
    casperShardConf: CasperShardConf,
    approvedBlock: BlockMessage,
    casperStateManager: CasperStateManager[F]
) extends MultiParentCasper[F] {

  import MultiParentCasperImpl._

  implicit private val logSource: LogSource = LogSource(this.getClass)

  // TODO: Extract hardcoded version from shard config
  private val version = 1L

  def getValidator: F[Option[ValidatorIdentity]] = validatorId.pure[F]

  def getVersion: F[Long] = version.pure[F]

  def getApprovedBlock: F[BlockMessage] = approvedBlock.pure[F]

//  private def updateLastFinalizedBlock(newBlock: BlockMessage): F[Unit] =
//    lastFinalizedBlock.whenA(
//      newBlock.body.state.blockNumber % casperShardConf.finalizationRate == 0
//    )

  /**
    * Check if there are blocks in CasperBuffer available with all dependencies met.
    *
    * @return First from the set of available blocks
    */
  override def getDependencyFreeFromBuffer: F[List[BlockMessage]] = {
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
      r <- depFreePendants.traverse(BlockStore[F].getUnsafe)
    } yield r
  }

  def dagContains(hash: BlockHash): F[Boolean] = blockDag.flatMap(_.contains(hash))

  def bufferContains(hash: BlockHash): F[Boolean] = CasperBufferStorage[F].contains(hash)

  def contains(hash: BlockHash): F[Boolean] = bufferContains(hash) ||^ dagContains(hash)

  def deploy(d: Signed[DeployData]): F[Either[DeployError, DeployId]] = {
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
    Estimator[F].tips(dag, approvedBlock).map(_.tips)

  def lastFinalizedBlock: F[BlockMessage] = {

    def processFinalised(finalizedSet: Set[BlockHash]): F[Unit] =
      finalizedSet.toList.traverse { h =>
        for {
          block          <- BlockStore[F].getUnsafe(h)
          deploys        = block.body.deploys.map(_.deploy)
          deploysRemoved <- DeployStorage[F].remove(deploys)
          _ <- Log[F].info(
                s"Removed $deploysRemoved deploys from deploy history as we finalized block ${PrettyPrinter
                  .buildString(finalizedSet)}."
              )
          _ <- BlockIndex.cache.remove(h).pure
        } yield ()
      }.void

    def newLfbFoundEffect(newLfb: BlockHash): F[Unit] =
      BlockDagStorage[F].recordDirectlyFinalized(newLfb, processFinalised) >>
        EventPublisher[F].publish(RChainEvent.blockFinalised(newLfb.base16String))

    implicit val ms = CasperMetricsSource

    for {
      dag                      <- blockDag
      lastFinalizedBlockHash   = dag.lastFinalizedBlock
      lastFinalizedBlockHeight <- dag.lookupUnsafe(lastFinalizedBlockHash).map(_.blockNum)
      work = Finalizer
        .run[F](
          dag,
          casperShardConf.faultToleranceThreshold,
          lastFinalizedBlockHeight,
          newLfbFoundEffect
        )
      newFinalisedHashOpt <- Span[F].traceI("finalizer-run")(work)
      blockMessage        <- BlockStore[F].getUnsafe(newFinalisedHashOpt.getOrElse(lastFinalizedBlockHash))
    } yield blockMessage
  }

  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation()

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

  def getRuntimeManager: F[RuntimeManager[F]] = Sync[F].delay(RuntimeManager[F])

  def fetchDependencies: F[Unit] = {
    import cats.instances.list._
    for {
      pendants       <- CasperBufferStorage[F].getPendants
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

  def validate(b: BlockMessage, s: CasperSnapshot[F]): F[Option[Offence]] =
    MultiParentCasperImpl.validate(b, s)

  def handleValidBlock(block: BlockMessage): F[BlockDagRepresentation[F]] =
    // TODO usage of blockDag here violates state access, state should be only in stateManager
    OptionT(MultiParentCasperImpl.validatedEff(block, none[Offence])).getOrElseF(blockDag)

  def handleInvalidBlock(
      block: BlockMessage,
      status: Offence,
      dag: BlockDagRepresentation[F]
  ): F[BlockDagRepresentation[F]] =
    // TODO usage of blockDag here violates state access, state should be only in stateManager
    OptionT(MultiParentCasperImpl.validatedEff(block, none[Offence])).getOrElseF(blockDag)
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

  def createdEvent(b: BlockMessage): RChainEvent = {
    val (blockHash, parents, justifications, deployIds, creator, seqNum) = blockEvent(b)
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

  def validate[F[_]: Concurrent: Span: Estimator: RuntimeManager: BlockStore: BlockDagStorage: CasperBufferStorage: Log: Time: Metrics](
      b: BlockMessage,
      s: CasperSnapshot[F]
  ): F[Option[Offence]] = {
    val validationProcess: OptionT[F, Offence] = for {
      _ <- Validate.blockSummary(b, s, s.onChainState.shardConf.shardName, deployLifespan)
      _ <- OptionT.liftF(Span[F].mark("post-validation-block-summary"))
      _ <- OptionT(InterpreterUtil.validateBlockCheckpoint(b, s, RuntimeManager[F]).flatMap {
            case Left(BlockException(ex)) => ex.raiseError[F, Option[Offence]]
            case Right(None)              => invalidTransaction.some.pure[F]
            case Right(Some(_))           => none[Offence].pure[F]
          })
      _ <- OptionT.liftF(Span[F].mark("transactions-validated"))
      _ <- OptionT(Validate.bondsCache(b, RuntimeManager[F]))
      _ <- OptionT.liftF(Span[F].mark("bonds-cache-validated"))
      _ <- OptionT(Validate.neglectedInvalidBlock(b, s))
      _ <- OptionT.liftF(Span[F].mark("neglected-invalid-block-validated"))
      _ <- OptionT(
            EquivocationDetector.checkNeglectedEquivocationsWithUpdate(b, s.dag)
          )
      _      <- OptionT.liftF(Span[F].mark("neglected-equivocation-validated"))
      depDag <- OptionT.liftF(CasperBufferStorage[F].toDoublyLinkedDag)
      status <- OptionT(EquivocationDetector.checkEquivocations(depDag, b, s.dag))
      _      <- OptionT.liftF(Span[F].mark("equivocation-validated"))
    } yield status

    val indexBlock = for {
      index <- BlockIndex[F, Par, BindPattern, ListParWithRandom, TaggedContinuation](
                b.blockHash,
                b.body.deploys,
                b.body.systemDeploys,
                Blake2b256Hash.fromByteString(b.body.state.preStateHash),
                Blake2b256Hash.fromByteString(b.body.state.postStateHash),
                RuntimeManager[F].getHistoryRepo
              )
      _ = BlockIndex.cache.putIfAbsent(b.blockHash, index)
    } yield ()

    val validationProcessDiag = for {
      // Create block and measure duration
      r                    <- Stopwatch.duration(validationProcess.value)
      (valResult, elapsed) = r
      _ <- valResult
            .map { status =>
              val blockInfo   = PrettyPrinter.buildString(b, short = true)
              val deployCount = b.body.deploys.size
              Log[F].info(s"Block replayed: $blockInfo (${deployCount}d) ($status) [$elapsed]") <*
                indexBlock.whenA(s.onChainState.shardConf.maxNumberOfParents > 1)
            }
            .getOrElse(().pure[F])
    } yield valResult

    Log[F].info(s"Validating block ${PrettyPrinter.buildString(b, short = true)}.") *> validationProcessDiag
  }

  def validatedEff[F[_]: Sync: BlockDagStorage: CasperBufferStorage: Log](
      block: BlockMessage,
      offence: Option[Offence]
  ): F[Option[BlockDagRepresentation[F]]] = {

    def handleInvalidBlockEffect(
        status: Offence,
        block: BlockMessage
    ): F[BlockDagRepresentation[F]] =
      for {
        _ <- Log[F].warn(
              s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
            )
        // TODO should be nice to have this transition of a block from casper buffer to dag storage atomic
        r <- BlockDagStorage[F].insert(block, invalid = true)
        _ <- CasperBufferStorage[F].remove(block.blockHash)
      } yield r

    offence match {
      case None =>
        for {
          updatedDag <- BlockDagStorage[F].insert(block, invalid = false)
          _          <- CasperBufferStorage[F].remove(block.blockHash)
        } yield updatedDag.some

      case Some(AdmissibleEquivocation) =>
        val baseEquivocationBlockSeqNum = block.seqNum - 1
        for {
          _ <- BlockDagStorage[F].accessEquivocationsTracker { tracker =>
                for {
                  equivocations <- tracker.equivocationRecords
                  _ <- Sync[F].unlessA(equivocations.exists {
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
          updatedDag <- handleInvalidBlockEffect(AdmissibleEquivocation, block)
        } yield updatedDag.some

      case Some(IgnorableEquivocation) =>
        /*
         * We don't have to include these blocks to the equivocation tracker because if any validator
         * will build off this side of the equivocation, we will get another attempt to add this block
         * through the admissible equivocations.
         */
        Log[F]
          .info(
            s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
          )
          .as(none[BlockDagRepresentation[F]])

      case Some(offence) =>
        CasperBufferStorage[F].remove(block.blockHash) >>
          Log[F]
            .warn(
              s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for $offence."
            ) >> {
          if (isSlashable(offence))
            handleInvalidBlockEffect(offence, block).map(_.some)
          else none[BlockDagRepresentation[F]].pure[F]
        }
    }
  }
}
