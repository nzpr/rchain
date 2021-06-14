package coop.rchain.casper

import cats.effect.Sync
import coop.rchain.blockstorage.dag.BlockDagRepresentation
import coop.rchain.casper.finality.Finalizer
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator

trait BlockDagRepresentationSyntax {
  implicit final def casperSyntaxBlockDagRepresentation[F[_]](
      dag: BlockDagRepresentation[F]
  ): BlockDagRepresentationOps[F] = new BlockDagRepresentationOps[F](dag)
}

final class BlockDagRepresentationOps[F[_]](private val dag: BlockDagRepresentation[F])
    extends AnyVal {

  /** find last finalized block, given set of latest messages. Use maxDepth put a constraint on search scope. */
  def findLastFinalizedBlock(
      latestMessagesView: List[(Validator, BlockMetadata)],
      faultToleranceThreshold: Float,
      searchDepth: Long = 100
  )(implicit syncF: Sync[F]): F[Option[BlockHash]] =
    Finalizer.findLastFinalizedBlock(dag, latestMessagesView, faultToleranceThreshold, searchDepth)
}
