package coop.rchain.casper.state

import coop.rchain.blockstorage.state.CasperStateValidated
import coop.rchain.casper.state.CasperState._
import coop.rchain.models.BlockHash.BlockHash

import scala.collection.immutable.Set

// Everything that identifies Casper state
final case class CasperState(
    // statuses of all casper messages known, validated and not
    statuses: Map[BlockHash, MessageStatus],
    // index of validated messages, content for BlockDagRepresentation
    validatedState: CasperStateValidated
) {
  // Should be called after message is received and effects are invoked (message persisted)
  def recordReceived(
      m: BlockHash,
      dependencies: Set[BlockHash]
  ): (CasperState, Boolean, Set[BlockHash], Set[BlockHash]) = {
    val missingSet = dependencies.diff(validatedState.dagSet)
    // whether all dependencies are validated, so message is ready for validation
    val ready = missingSet.isEmpty
    // messages that state is not aware of should be requested
    val toRequest = missingSet.filterNot(statuses.contains)
    val nextSt = {
      val mStatus = if (ready) ReceivedReady else ReceivedWaiting(missingSet)
      val newStatuses =
        toRequest.foldLeft((statuses + (m -> mStatus)))((st, mis) => st + (mis -> Requested))
      CasperState(
        newStatuses,
        validatedState
      )
    }

    (nextSt, ready, missingSet, toRequest)
  }

  // Should be called after message is validated and effects of validation invoked
  def recordValidated(m: BlockHash, newDagST: CasperStateValidated): CasperState = {
    require(
      statuses.contains(m),
      "Casper message processor state is inconsistent, calling 'done' on unknown message."
    )
    require(
      !validatedState.dagSet.contains(m),
      "Casper message processor state is inconsistent, calling 'done' on validated message."
    )
    val (newReady, toAdjust) = statuses
      .collect {
        case (h, ReceivedWaiting(missing)) if missing.contains(h) =>
          h -> ReceivedWaiting(missing - h)
      }
      .partition { case (_, ReceivedWaiting(v)) => v.isEmpty }
    val newStatuses = (newReady.toIterator ++ toAdjust).foldLeft(statuses) {
      case (acc, (m, ReceivedWaiting(missing))) =>
        if (missing.isEmpty) acc + (m -> ReceivedReady)
        else acc + (m                 -> ReceivedWaiting(missing))
    }
    CasperState(
      newStatuses,
      newDagST
    )
  }

  def known(m: BlockHash): Boolean = statuses.contains(m) || validatedState.dagSet.contains(m)
  def beforeFinalized(height: Long): Boolean =
    validatedState.lastFinalizedBlock.exists { case (_, h) => h >= height }
  val readySet = statuses.collect { case (m, ReceivedReady) => m }.toSet
}

object CasperState {
  trait MessageStatus
  // Required but not available. Short living status
  case object Missing extends MessageStatus
  // Has been requested from the network
  case object Requested extends MessageStatus
  // Received and accepted by message processor
  case object ReceivedReady extends MessageStatus
  // Cannot be validated as dependency messages are not processed
  final case class ReceivedWaiting[M](dependencies: Set[M]) extends MessageStatus
  // Validation is in progress
  case object Processing extends MessageStatus
  // Validated
  case object Validated extends MessageStatus

  // No known messages, validated state is empty
  def empty                          = CasperState(Map.empty, CasperStateValidated.empty)
  def isEmpty(casperST: CasperState) = casperST.statuses.isEmpty
}
