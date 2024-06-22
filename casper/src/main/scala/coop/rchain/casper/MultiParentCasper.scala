package coop.rchain.casper

import cats.data.EitherT
import cats.effect.{Async, Sync}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.BlockStore.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag.{BlockDagStorage, Finalizer}
import coop.rchain.casper.merging.{BlockIndex, MergeScope, ParentsMergedState}
import coop.rchain.casper.protocol._
import coop.rchain.casper.rholang.{InterpreterUtil, RuntimeManager}
import coop.rchain.casper.syntax._
import coop.rchain.crypto.signatures.Signed
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.syntax._
import coop.rchain.models.{BlockHash => _, _}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.history.RadixTree
import coop.rchain.sdk.error.FatalError
import coop.rchain.sdk.syntax.all.mapSyntax
import coop.rchain.shared._
import coop.rchain.shared.syntax.sharedSyntaxKeyValueTypedStore

import scala.concurrent.duration.DurationInt

final case class ParsingError(details: String)

object MultiParentCasper {

  // TODO: copied from previous code
  //  - remove it, no need to have error message "Error: error ... of error"
  def parsingError(details: String) = ParsingError(s"Parsing error: $details")

  // TODO: Extract hardcoded deployLifespan from shard config
  // Size of deploy safety range.
  // Validators will try to put deploy in a block only for next `deployLifespan` blocks.
  // Required to enable protection from re-submitting duplicate deploys
  val deployLifespan = 50

  def getPreStateForNewBlock[F[_]: Async: RuntimeManager: BlockDagStorage: BlockStore: Log]
      : F[ParentsMergedState] =
    for {
      dag <- BlockDagStorage[F].getRepresentation

      // TEMP: take bonds map from first latest message if finalized fringe is not available
      latestMsgs = dag.dagMessageState.latestMsgs

      parentHashes = latestMsgs.map(_.id)

      preState <- getPreStateForParents(parentHashes)
    } yield preState

  def getPreStateForParents[F[_]: Async: RuntimeManager: BlockDagStorage: BlockStore: Log](
      parentHashes: Set[BlockHash]
  ): F[ParentsMergedState] =
    for {
      _ <- FatalError(
            "Parents must not be empty to calculate pre-state. Genesis block pre-state is loaded from config."
          ).raiseError.whenA(parentHashes.isEmpty)

      dag <- BlockDagStorage[F].getRepresentation

      justifications <- parentHashes.toList.traverse(BlockDagStorage[F].lookupUnsafe(_))

      // Calculate finalized fringe from justifications
      msgMap  = dag.dagMessageState.msgMap
      parents = parentHashes.map(msgMap)
      // Get currently finalized bonds map
      prevFringe       = dag.dagMessageState.msgMap.latestFringe(parents)
      prevFringeHashes = prevFringe.map(_.id)
      // Previous fringe state should be present (loaded from BlockMetadata store)
      fringeRecord <- dag.fringeStates
                       .get(prevFringeHashes)
                       .liftTo {
                         val fringeStr = PrettyPrinter.buildString(prevFringeHashes)
                         val errMsg =
                           s"Fringe state not available in state cache, fringe: $fringeStr"
                         FatalError(errMsg)
                       }

      prevFringeState           = fringeRecord.stateHash
      prevFringeRejectedDeploys = fringeRecord.rejectedDeploys
      prevFringeStateHash       = prevFringeState.toByteString
      // TODO: for empty fringe bonds map should be loaded from bonds file (if validated in replay)
      bondsMap <- if (prevFringe.isEmpty || prevFringeStateHash == RuntimeManager.emptyStateHashFixed)
                   justifications.head.bondsMap.pure[F]
                 else
                   RuntimeManager[F].computeBonds(prevFringeStateHash)

      finalizer = Finalizer(dag.dagMessageState.msgMap)
      (_, newFringesFound) = finalizer
        .calculateFinalization(parents, bondsMap)

      _ <- Log[F]
            .info(s"Found ${newFringesFound.size} new fringes: ${newFringesFound
              .map(_.map(_.id.toHexString.take(8)).toList.sorted)}")
            .whenA(newFringesFound.nonEmpty)

      // If new fringe is finalized, merge it
      newFringeResult <- newFringesFound.nonEmpty
                          .guard[Option]
                          .as(newFringesFound.map(_.map(_.id)))
                          .traverse { fringes =>
                            def doMerge(
                                prevFringeStateHash: Blake2b256Hash,
                                prevFringe: Set[BlockHash],
                                fringe: Set[BlockHash]
                            ): F[(Blake2b256Hash, Set[BlockHash])] = {
                              val mergeFringe = {
                                val (mScope, _) =
                                  MergeScope.fromDag(
                                    fringe,
                                    prevFringe,
                                    dag.childMap,
                                    msgMap,
                                    dag.hashLookup.getUnsafe
                                  )
                                for {
                                  result <- MergeScope
                                             .merge(
                                               mScope,
                                               prevFringeStateHash,
                                               dag.fringeStates,
                                               RuntimeManager[F].getHistoryRepo,
                                               BlockIndex.getBlockIndex[F](_)
                                             )
                                } yield result -> mScope.conflictScope
                              }
                              mergeFringe.flatMap {
                                case result -> cScope =>
                                  val (finalizedState, rejected) = result
                                  val msgFinalized =
                                    s"New finalized fringe. " +
                                      s"${prevFringeHashes.map(_.toHexString.take(8)).toList.sorted} @ $prevFringeState => " +
                                      s"${fringe.map(_.toHexString.take(8)).toList.sorted} @ $finalizedState. " +
                                      s"ConflictScope: ${cScope.map(_.toHexString.take(8)).toList.sorted}. " +
                                      s"RejectedDeploys: ${rejected.map(_.toHexString.take(8)).toList.sorted}"
                                  Log[F].info(msgFinalized).as(result)
                              }
                            }

                            fringes.foldM((prevFringeState, prevFringeHashes, Set.empty[BlockHash])) {
                              case ((prevFS, prevF, rjAcc), newF) =>
                                doMerge(prevFS, prevF, newF).map {
                                  case (newSt, rj) => (newSt, newF, rjAcc ++ rj)
                                }
                            }
                          }
      (fringeState, newFringe, rejectedDeploys) = newFringeResult getOrElse (prevFringeState, prevFringeHashes, prevFringeRejectedDeploys)

      maxHeight  = justifications.map(_.blockNum).maximumOption.getOrElse(-1L)
      maxSeqNums = justifications.map(m => (m.sender, m.seqNum)).toMap

      // Merge conflict scope (non-finalized blocks above fringe)
      minGenJs = MergeScope.minGenJs(parentHashes, dag)
      conflictScopeMergeResult <- minGenJs.toSeq match {
                                   case _ =>
                                     val (mScope, _) =
                                       MergeScope.fromDag(
                                         parentHashes,
                                         newFringe,
                                         dag.childMap,
                                         msgMap,
                                         dag.hashLookup.getUnsafe
                                       )
                                     MergeScope
                                       .merge(
                                         mScope,
                                         fringeState,
                                         dag.fringeStates,
                                         RuntimeManager[F].getHistoryRepo,
                                         BlockIndex.getBlockIndex[F](_)
                                       )
                                 }
      (preStateHash, csRejectedDeploys) = conflictScopeMergeResult

      csRejectedDeploysStr = PrettyPrinter.buildString(csRejectedDeploys)
      fringeRejectedStr    = PrettyPrinter.buildString(fringeRecord.rejectedDeploys)
      infoMsg = s"Computed parents post state, fringe rejections: $fringeRejectedStr, " +
        s"rejections: $csRejectedDeploysStr"
      _ <- Log[F].info(infoMsg)
    } yield ParentsMergedState(
      justifications = justifications.toSet,
      maxHeight,
      maxSeqNums,
      fringe = newFringe,
      fringeState = fringeState,
      fringeBondsMap = bondsMap,
      fringeRejectedDeploys = rejectedDeploys,
      preStateHash = preStateHash,
      rejectedDeploys = csRejectedDeploys
    )

  def validate[F[_]: Async: RuntimeManager: BlockDagStorage: BlockStore: Metrics: Span](
      block: BlockMessage,
      shardId: String,
      minPhloPrice: Long
  ): F[Either[(BlockMetadata, InvalidBlock), BlockMetadata]] = {
    Log.traced[F](block.justifications.map(_.toByteArray).toSet, block.sender.toByteArray).use {
      implicit tracedLog =>
        val initBlockMeta = BlockMetadata.fromBlock(block)

        val validateSummary = EitherT(Validate.blockSummary(block, shardId, deployLifespan))
          .as(initBlockMeta)
          .leftMap(e => (initBlockMeta, e))

        val validationProcess: EitherT[F, (BlockMetadata, InvalidBlock), BlockMetadata] =
          for {
            _ <- validateSummary
            // validate view
            _                                <- EitherT.liftF(Validate.view(block))
            _                                <- EitherT.liftF(Span[F].mark("post-validation-block-summary"))
            validated                        <- EitherT.liftF(InterpreterUtil.validateBlockCheckpoint(block))
            (blockMetadata, validatedResult) = validated
            _ <- EitherT.fromEither(validatedResult match {
                  case Left(ex)     => Left((blockMetadata, ex))
                  case Right(true)  => Right(blockMetadata)
                  case Right(false) => Left((blockMetadata, BlockStatus.invalidStateHash))
                })
            _ <- EitherT.liftF(Span[F].mark("transactions-validated"))
            _ <- EitherT(Validate.bondsCache(block))
                  .as(blockMetadata)
                  .leftMap(e => (blockMetadata, e))
            _ <- EitherT.liftF(Span[F].mark("bonds-cache-validated"))
            _ <- EitherT(Validate.neglectedInvalidBlock(block))
                  .as(blockMetadata)
                  .leftMap(e => (blockMetadata, e))
            _ <- EitherT.liftF(Span[F].mark("neglected-invalid-block-validated"))

            // This validation is only to punish validator which accepted lower price deploys.
            // And this can happen if not configured correctly.
            status <- EitherT(Validate.phloPrice(block, minPhloPrice))
                       .recoverWith {
                         case _ =>
                           val warnToLog = EitherT.liftF[F, InvalidBlock, Unit](
                             Log[F]
                               .warn(
                                 s"One or more deploys has phloPrice lower than $minPhloPrice"
                               )
                           )
                           val asValid = EitherT.rightT[F, InvalidBlock](BlockStatus.valid)
                           warnToLog *> asValid
                       }
                       .as(blockMetadata)
                       .leftMap(e => (blockMetadata, e))
            _ <- EitherT.liftF(Span[F].mark("phlogiston-price-validated"))
          } yield status

        val blockPreState  = block.preStateHash
        val blockPostState = block.postStateHash
        val blockSender    = block.sender.toByteArray

        val indexBlock = for {
          mergeableChs <- RuntimeManager[F].loadMergeableChannels(
                           blockPostState,
                           blockSender,
                           block.seqNum
                         )

          index <- BlockIndex(
                    block.blockHash,
                    block.state.deploys,
                    block.state.systemDeploys,
                    blockPreState.toBlake2b256Hash,
                    blockPostState.toBlake2b256Hash,
                    RuntimeManager[F].getHistoryRepo,
                    mergeableChs
                  )
          _ = BlockIndex.cache.putIfAbsent(block.blockHash, index)
        } yield ()

        val validationProcessDiag = for {
          // Create block and measure duration
          r                    <- Stopwatch.duration(validationProcess.value)
          (valResult, elapsed) = r
          // TODO: update validated fields in a more clear way
          valResultUpdated <- valResult
                               .map { blockMeta =>
                                 val blockInfo   = PrettyPrinter.buildString(block, short = true)
                                 val deployCount = block.state.deploys.size
                                 Log[F].info(
                                   s"Block replayed: $blockInfo (${deployCount}d) (Valid) [$elapsed]"
                                 ) *>
                                   indexBlock as blockMeta
                                   .copy(validated = true)
                                   .asRight[(BlockMetadata, InvalidBlock)]
                               }
                               .leftMap {
                                 case (blockMeta, err) =>
                                   val deployCount = block.state.deploys.size
                                   val blockInfo   = PrettyPrinter.buildString(block, short = true)
                                   Log[F].warn(
                                     s"Block replayed: $blockInfo (${deployCount}d) ($err) [$elapsed]"
                                   ) as
                                     (
                                       blockMeta.copy(validated = true, validationFailed = true),
                                       err
                                     ).asLeft[BlockMetadata]
                               }
                               .merge
        } yield valResultUpdated

        Log[F]
          .info(s"Validating block ${PrettyPrinter.buildString(block)}.") *> validationProcessDiag
    }
  }

  def lastFinalizedBlock[F[_]: Sync: BlockDagStorage: BlockStore]: F[BlockMessage] =
    for {
      dag          <- BlockDagStorage[F].getRepresentation
      blockMessage <- dag.lastFinalizedBlockUnsafe.flatMap(BlockStore[F].getUnsafe)
    } yield blockMessage

  def deploy[F[_]: Sync: BlockDagStorage: Log](
      d: Signed[DeployData]
  ): F[Either[ParsingError, DeployId]] = {
    import coop.rchain.models.rholang.implicits._
    InterpreterUtil
      .mkTerm(d.data.term, NormalizerEnv(d))
      .flatMap(_ => addDeploy(d))
      .attempt
      .map(_.leftMap(err => parsingError(s"Error in parsing term: \n$err")))
  }

  private def addDeploy[F[_]: Sync: BlockDagStorage: Log](deploy: Signed[DeployData]): F[DeployId] =
    for {
      _ <- BlockDagStorage[F].addDeploy(deploy)
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield deploy.sig
}
