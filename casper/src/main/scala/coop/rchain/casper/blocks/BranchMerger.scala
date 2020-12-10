package coop.rchain.casper.blocks

import cats.effect.Concurrent
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.EstimatorHelper
import coop.rchain.casper.protocol.ProcessedDeploy
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.util.Lib
import coop.rchain.state.DAGReader
import coop.rchain.state.DAGReader._

import scala.collection.Seq

object BranchMerger {

  final case class MergingVertex(
      blockHash: BlockHash = ByteString.EMPTY,
      stateHash: StateHash,
      processedDeploys: Seq[ProcessedDeploy]
  )

  final case class MergingBranch(
      finalStateHash: StateHash,
      vertices: Seq[MergingVertex]
  )

  final class MergingDagReader[F[_]: Concurrent: BlockStore](hashDAGReader: DAGReader[F, BlockHash])
      extends DAGReader[F, MergingVertex] {
    override def children(vertex: MergingVertex): F[Option[Set[MergingVertex]]] =
      hashDAGReader.children(vertex.blockHash).flatMap {
        case None => none[Set[MergingVertex]].pure[F]
        case Some(children) =>
          BlockStore[F]
            .getUnsafe(children.toSeq)
            .compile
            .toList
            .map(
              _.map(b => MergingVertex(b.blockHash, b.body.state.postStateHash, b.body.deploys)).toSet.some
            )
      }

    override def parents(vertex: MergingVertex): F[Option[Set[MergingVertex]]] =
      hashDAGReader.parents(vertex.blockHash).flatMap {
        case None => none[Set[MergingVertex]].pure[F]
        case Some(children) =>
          BlockStore[F]
            .getUnsafe(children.toSeq)
            .compile
            .toList
            .map(
              _.map(b => MergingVertex(b.blockHash, b.body.state.postStateHash, b.body.deploys)).toSet.some
            )
      }
  }

  /**
    * Merges DAG.
    * @param tips - top blocks of the DAG
    * @param base - bottom blocks of the DAG, all tips should be conneted with base
    * @param dag - connection graph
    * @return tuplespace state hash of the merged state and list of conflicting deploys to reject
    */
  def merge[F[_]: Concurrent](
      tips: Seq[MergingVertex],
      base: MergingVertex,
      dag: DAGReader[F, MergingVertex]
  )(implicit runtimeManager: RuntimeManager[F]): F[(StateHash, Seq[ProcessedDeploy])] = {
    val computeBranches: F[Seq[MergingBranch]] =
      for {
        // compute branches in parallel
        branches <- fs2.Stream
                     .emits(tips.map { top =>
                       fs2.Stream.eval(computeBranch(top, base, dag))
                     })
                     .parJoinUnbounded
                     .compile
                     .toList
                     .map(_.map(b => MergingBranch(b._1.stateHash, b._2)))
        // sort branches by number of blocks contained
        sorted = branches.sortBy(_.vertices.size)
        // deduplicate branches content
        // check the biggest branch and remove from remaining ones blocks that the biggest branch contains.
        // go to the second biggest, repeat.
        (_, nonIntersectingBranches) = sorted.foldLeft(
          // initial list of branches and result list of deduplicated branches
          (sorted, Seq.empty[MergingBranch])
        ) {
          case ((biggestBranch :: remainder, result), _) => {
            // remove from remainder branches blocks that are also in the biggestBranch
            val filteredRemainder = remainder.map { b =>
              MergingBranch(b.finalStateHash, b.vertices.filterNot(biggestBranch.vertices.contains))
            }
            (filteredRemainder, result :+ biggestBranch)
          }
        }
      } yield nonIntersectingBranches

    for {
      branches <- computeBranches
      // biggest branch - main :: remainder branches - merging
      mainBranch :: toMerge = branches
      // init state: biggest branch, rejected deploys are empty
      accInit = (
        (
          mainBranch,
          List.empty[ProcessedDeploy]
        )
      )
      // fold through merging branches and merge them all one by one.
      r <- toMerge.foldLeftM[F, (MergingBranch, Seq[ProcessedDeploy])](
            accInit
          ) {
            case (
                (mainBranch, rejectedDeploys),
                mergingBranch
                ) => {
              import coop.rchain.rholang.interpreter.storage._
              for {
                changes <- Lib.time(
                            s"COMPUTE MERGE CHANGES: main deploys ${mainBranch.vertices
                              .flatMap(_.processedDeploys)
                              .size}, merging deploys ${mergingBranch.vertices.flatMap(_.processedDeploys).size}"
                          )(
                            EstimatorHelper.computeMergeChanges(
                              runtimeManager.getHistoryRepo,
                              Blake2b256Hash.fromByteString(base.stateHash),
                              mainBranch.vertices.flatMap(_.processedDeploys).toList,
                              mergingBranch.vertices.flatMap(_.processedDeploys).toList
                            )
                          )
                mergeStateHash <- Lib.time(
                                   s"MERGE TWO BRANCHES, events: ${changes.validEventLogs.size}"
                                 )(
                                   runtimeManager.getHistoryRepo.stateMerger.flatMap(
                                     _.merge(
                                       Blake2b256Hash.fromByteString(base.stateHash),
                                       Blake2b256Hash.fromByteString(mainBranch.finalStateHash),
                                       Blake2b256Hash.fromByteString(mergingBranch.finalStateHash),
                                       changes.validEventLogs.toList
                                     )
                                   )
                                 )
                newMainBranch = MergingBranch(
                  mergeStateHash.toByteString,
                  mainBranch.vertices ++ mergingBranch.vertices
                )
              } yield (
                newMainBranch,
                rejectedDeploys ++ changes.rejectedDeploys
              )
            }
          }
      (mergedBranch, rejectedDeploys) = r
    } yield (mergedBranch.finalStateHash, rejectedDeploys)
  }
}
