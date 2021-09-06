package coop.rchain.blockstorage.state

import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.models.block.StateHash.StateHash
import cats.syntax.all._

import scala.collection.immutable.{Set, SortedMap}

// Index of Casper validated state
// Though it belongs to Casper, we have to create it in block storage project, as it is effectively
// content of BlockDagRepresentation
final case class CasperStateValidated(
    dagSet: Set[BlockHash],
    latestMessagesMap: Map[Validator, BlockHash],
    childMap: Map[BlockHash, Set[BlockHash]],
    heightMap: SortedMap[Long, Set[BlockHash]],
    invalidBlocksSet: Set[BlockHash],
    lastFinalizedBlock: Option[(BlockHash, Long)],
    finalizedBlocksSet: Set[BlockHash],
    validStatesCounter: Map[StateHash, Int]
)

object CasperStateValidated {
  def empty =
    CasperStateValidated(
      Set.empty,
      Map.empty,
      Map.empty,
      SortedMap.empty,
      Set.empty,
      none[(BlockHash, Long)],
      Set.empty,
      Map.empty
    )

  // Each state have to have some block finalized, otherwise it is considered empty
  def isEmpty(st: CasperStateValidated) = st.lastFinalizedBlock.isEmpty
}
