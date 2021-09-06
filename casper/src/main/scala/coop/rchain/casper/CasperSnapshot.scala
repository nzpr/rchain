package coop.rchain.casper

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.blockstorage.state.CasperStateValidated
import coop.rchain.casper.finality.Finalizer
import coop.rchain.casper.protocol.{BlockMessage, DeployData, Justification}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.crypto.signatures.Signed
import coop.rchain.dag.DagOps
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.casper.syntax._
import coop.rchain.shared.syntax._

/**
  * Casper snapshot is a state that is changing in discrete manner with each new block added.
  * This class represents full information about the state. It is required for creating new blocks
  * as well as for validating blocks.
  */
final case class CasperSnapshot[F[_]](
    dag: BlockDagRepresentation[F],
    lastFinalizedBlock: BlockHash,
    parents: List[BlockMessage],
    justifications: Set[Justification],
    invalidBlocks: Map[Validator, BlockHash],
    deploysInScope: Set[Signed[DeployData]],
    maxBlockNum: Long,
    maxSeqNums: Map[Validator, Int],
    onChainState: OnChainCasperState,
    acquiescence: Boolean
)

object CasperSnapshot {
  def apply[F[_]: Sync: RuntimeManager: BlockStore: BlockDagStorage: Estimator](
      targetMessageOpt: Option[BlockMessage],
      targetState: Option[CasperStateValidated] = None,
      casperShardConf: CasperShardConf
  ): F[CasperSnapshot[F]] = {
    import cats.instances.list._

    def computeOnChainState(b: BlockMessage): F[OnChainCasperState] =
      for {
        av <- RuntimeManager[F].getActiveValidators(b.body.state.postStateHash)
        // bonds are available in block message, but please remember this is just a cache, source of truth is RSpace.
        bm          = b.body.state.bonds
        shardConfig = casperShardConf
      } yield OnChainCasperState(shardConfig, bm.map(v => v.validator -> v.stake).toMap, av)

    // parents do not include invalid latest messages and share the same bonds map
    def computeParents(
        dag: BlockDagRepresentation[F],
        approvedBlock: BlockMessage
    ): F[List[BlockMessage]] =
      for {
        r         <- Estimator[F].tips(dag, approvedBlock)
        (_, tips) = (r.lca, r.tips)

        /**
          * Before block merge, `EstimatorHelper.chooseNonConflicting` were used to filter parents, as we could not
          * have conflicting parents. With introducing block merge, all parents that share the same bonds map
          * should be parents. Parents that have different bond maps are only one that cannot be merged in any way.
          */
        // For now main parent bonds map taken as a reference, but might be we want to pick a subset with equal
        // bond maps that has biggest cumulative stake.
        blocks  <- tips.toList.traverse(BlockStore[F].getUnsafe)
        parents = blocks.filter(b => b.body.state.bonds == blocks.head.body.state.bonds)
      } yield parents

    /**
      * Justifications might include invalid latest messages and should be bonded in parents
      * We ensure that only the justifications given in the block are those
      * which are bonded validators in the chosen parent. This is safe because
      * any latest message not from a bonded validator will not change the
      * final fork-choice.
      */
    def computeJustifications(
        dag: BlockDagRepresentation[F],
        onChainState: OnChainCasperState
    ): F[Map[Validator, BlockHash]] =
      dag.latestMessageHashes.map(_.filterKeys(onChainState.bondsMap.keySet.contains(_)))

    for {
      // the most recent view on the DAG, includes everything node seen so far
      fullDag <- BlockDagStorage[F].getRepresentation(targetState)
      approvedBlock <- fullDag.getPureState.lastFinalizedBlock
                        .map { case (hash, _) => hash }
                        .liftTo(new Exception(s"Calling estimator on empty Casper state"))
                        .flatMap(BlockStore[F].getUnsafe)

      getBlockView = (m: BlockMessage) =>
        for {
          p <- m.header.parentsHashList.traverse(BlockStore[F].getUnsafe)
          s <- computeOnChainState(p.head)
          j = m.justifications.map { case Justification(v, h) => (v, h) }.toMap

          findLfb = (latestMessages: Map[Validator, BlockHash]) =>
            for {
              minNum       <- p.map(_.blockHash).traverse(fullDag.lookupUnsafe).map(_.map(_.blockNum).min)
              lowestHeight = minNum - Finalizer.MaxSearchDepth // lowest height puts a constraint on search area

              lfb <- fullDag
                      .findLastFinalizedBlock(
                        latestMessagesView = latestMessages,
                        faultToleranceThreshold = casperShardConf.faultToleranceThreshold,
                        lowestHeight = lowestHeight
                      )
                      .flatMap { lfbOpt =>
                        // if approved block is in search range - return it.
                        // This is required because genesis has fault tolerance less then max value so wont be finalized for
                        // all thresholds.
                        // Also in future approved block restored from LFS might be finalized with fault tolerance less then current
                        // fault tolerance from shard config

                        val approvedBlockIsInRange = fullDag
                          .lookupUnsafe(approvedBlock.blockHash)
                          .map(_.blockNum)
                          .map(_ >= lowestHeight)

                        val notFoundF = approvedBlockIsInRange.ifM(
                          approvedBlock.blockHash.pure[F], {
                            val lfbNotFoundErrMsg = s"No last finalized block found when creating casper snapshot for " +
                              s"${targetMessageOpt.map(PrettyPrinter.buildString(_)).getOrElse("the most recent view")}."
                            new Exception(lfbNotFoundErrMsg).raiseError[F, BlockHash]
                          }
                        )
                        lfbOpt.map(_.pure[F]).getOrElse(notFoundF)
                      }
            } yield lfb
          dagView <- fullDag.truncate(j, findLfb)
        } yield (dagView, p, s, j)

      getLatestView = for {
        p <- computeParents(fullDag, approvedBlock)
        s <- computeOnChainState(p.head)
        j <- computeJustifications(fullDag, s)
      } yield (fullDag, p, s, j)

      // if target message supplied, create dag view, otherwise get the most recent view
      view                                         <- targetMessageOpt.map(getBlockView).getOrElse(getLatestView)
      (dag, parents, onChainState, justifications) = view
      lfb                                          = dag.lastFinalizedBlock

      parentMetas <- parents.map(_.blockHash).traverse(dag.lookupUnsafe)
      maxBlockNum = parentMetas.map(_.blockNum).max
      maxSeqNums <- justifications.toList
                     .traverse {
                       case (v, h) => dag.lookupUnsafe(h).map((v -> _.seqNum))
                     }
                     .map(_.toMap)
      deploysInScope <- {
        val currentBlockNumber  = maxBlockNum + 1
        val earliestBlockNumber = currentBlockNumber - onChainState.shardConf.deployLifespan
        for {
          result <- DagOps
                     .bfTraverseF[F, BlockMetadata](parentMetas)(
                       b =>
                         ProtoUtil
                           .getParentMetadatasAboveBlockNumber(
                             b,
                             earliestBlockNumber,
                             dag
                           )
                     )
                     .foldLeftF(Set.empty[Signed[DeployData]]) { (deploys, blockMetadata) =>
                       for {
                         block        <- BlockStore[F].getUnsafe(blockMetadata.blockHash)
                         blockDeploys = ProtoUtil.deploys(block).map(_.deploy)
                       } yield deploys ++ blockDeploys
                     }
        } yield result
      }
      invalidBlocks <- dag.invalidBlocksMap
      acquiescence  <- dag.reachedAcquiescence
    } yield CasperSnapshot(
      dag,
      lfb,
      parents,
      justifications.map { case (v, h) => Justification(v, h) }.toSet,
      invalidBlocks,
      deploysInScope,
      maxBlockNum,
      maxSeqNums,
      onChainState,
      acquiescence
    )
  }
}
