package coop.rchain.casper

import coop.rchain.models.BlockHash.BlockHash

sealed trait BlockStatus

// Possible outcomes of block processing
object BlockStatus {
  // Ignored, without saving to block storage
  final case class Ignored(reason: IgnoreReason) extends BlockStatus
  // Missing dependencies
  final case class Incomplete(dependencies: Set[BlockHash]) extends BlockStatus
  // Exception during processing
  final case class BlockException(ex: Throwable) extends BlockStatus

  // Validated successfully
  final case class Validated(offenceDetected: Option[Offence]) extends BlockStatus

  // Reasons for block to be ignored
  sealed trait IgnoreReason
  case object InvalidFormat      extends IgnoreReason
  case object MalformedSignature extends IgnoreReason
  case object MalformedSigAlg    extends IgnoreReason
  case object MalformedSender    extends IgnoreReason
  case object MalformedShard     extends IgnoreReason
  case object MalformedPostState extends IgnoreReason
  case object WrongVersion       extends IgnoreReason
  case object WrongTimestamp     extends IgnoreReason
  case object Known              extends IgnoreReason
  case object Old                extends IgnoreReason

  def malformedSignature: IgnoreReason = MalformedSignature
  def malformedSigAlg: IgnoreReason    = MalformedSigAlg
  def malformedSender: IgnoreReason    = MalformedSender
  def malformedShard: IgnoreReason     = MalformedShard
  def malformedPostState: IgnoreReason = MalformedPostState
  def wrongVersion: IgnoreReason       = WrongVersion
  def wrongTimestamp: IgnoreReason     = WrongTimestamp

  // Reasons for block to be marked as invalid
  sealed trait Offence
  // AdmissibleEquivocation are blocks that would create an equivocation but are
  // pulled in through a justification of another block
  case object AdmissibleEquivocation extends Offence
  // TODO: Make IgnorableEquivocation slashable again and remember to add an entry to the equivocation record.
  // For now we won't eagerly slash equivocations that we can just ignore,
  // as we aren't forced to add it to our view as a dependency.
  // TODO: The above will become a DOS vector if we don't fix.
  case object IgnorableEquivocation extends Offence

  case object DeployNotSigned         extends Offence
  case object InvalidBlockNumber      extends Offence
  case object InvalidRepeatDeploy     extends Offence
  case object InvalidParents          extends Offence
  case object InvalidFollows          extends Offence
  case object InvalidSequenceNumber   extends Offence
  case object InvalidShardId          extends Offence
  case object JustificationRegression extends Offence
  case object NeglectedInvalidBlock   extends Offence
  case object NeglectedEquivocation   extends Offence
  case object InvalidTransaction      extends Offence
  case object InvalidBondsCache       extends Offence
  case object InvalidBlockHash        extends Offence
  case object InvalidRejectedDeploy   extends Offence
  case object ContainsExpiredDeploy   extends Offence
  case object ContainsFutureDeploy    extends Offence

  def admissibleEquivocation: Offence  = AdmissibleEquivocation
  def ignorableEquivocation: Offence   = IgnorableEquivocation
  def deployNotSigned: Offence         = DeployNotSigned
  def invalidBlockNumber: Offence      = InvalidBlockNumber
  def invalidRejectedDeploy: Offence   = InvalidRejectedDeploy
  def invalidRepeatDeploy: Offence     = InvalidRepeatDeploy
  def invalidParents: Offence          = InvalidParents
  def invalidFollows: Offence          = InvalidFollows
  def invalidSequenceNumber: Offence   = InvalidSequenceNumber
  def invalidShardId: Offence          = InvalidShardId
  def justificationRegression: Offence = JustificationRegression
  def neglectedInvalidBlock: Offence   = NeglectedInvalidBlock
  def neglectedEquivocation: Offence   = NeglectedEquivocation
  def invalidTransaction: Offence      = InvalidTransaction
  def invalidBondsCache: Offence       = InvalidBondsCache
  def invalidBlockHash: Offence        = InvalidBlockHash
  def containsExpiredDeploy: Offence   = ContainsExpiredDeploy
  def containsFutureDeploy: Offence    = ContainsFutureDeploy

  val slashableOffenses: Set[Offence] =
    Set(
      AdmissibleEquivocation,
      DeployNotSigned,
      InvalidBlockNumber,
      InvalidRepeatDeploy,
      InvalidParents,
      InvalidFollows,
      InvalidSequenceNumber,
      InvalidShardId,
      JustificationRegression,
      NeglectedInvalidBlock,
      NeglectedEquivocation,
      InvalidTransaction,
      InvalidBondsCache,
      InvalidBlockHash,
      InvalidRejectedDeploy,
      ContainsExpiredDeploy,
      ContainsFutureDeploy
    )

  def isSlashable(invalidBlock: Offence): Boolean =
    slashableOffenses.contains(invalidBlock)
}
