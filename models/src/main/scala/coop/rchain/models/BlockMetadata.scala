package coop.rchain.models

import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.models.block.StateHash.StateHash
import coop.rchain.models.syntax._
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.history.instances.RadixHistory
import coop.rchain.sdk.dag.View

final case class BlockMetadata(
    blockHash: BlockHash,
    blockNum: Long,
    sender: Validator,
    seqNum: Long,
    justifications: Set[BlockHash],
    bondsMap: Map[Validator, Long],
    // Replay status
    validated: Boolean,
    validationFailed: Boolean,
    // Finalization fringe seen by this block
    fringe: Set[BlockHash],
    fringeStateHash: StateHash,
    // Fringe (fringe hash) where/when block is finalized
    memberOfFringe: Option[Blake2b256Hash],
    view: View[Validator]
) {
  // BlockMetadata is uniquely identified with BlockHash
  // - overridden hashCode is to be more performant when used in Set or Map
  override def hashCode(): Int = blockHash.hashCode()
}

object BlockMetadata {
  def from(b: BlockMetadataProto) = BlockMetadata(
    b.blockHash,
    b.blockNum,
    b.sender,
    b.seqNum,
    b.justifications.toSet,
    b.bonds.map(b => b.validator -> b.stake).toMap,
    b.validated,
    b.validationFailed,
    b.fringe.toSet,
    b.fringeStateHash,
    Option(b.memberOfFringe).filterNot(_.isEmpty).map(_.toBlake2b256Hash),
    View(b.view.map(x => x.validator -> (x.seqStart.toInt to x.seqEnd.toInt)).toMap)
  )

  def toProto(b: BlockMetadata) = BlockMetadataProto(
    b.blockHash,
    b.blockNum,
    b.sender,
    b.seqNum,
    b.justifications.toList,
    b.bondsMap.map { case (validator, stake) => BondProto(validator, stake) }.toList,
    b.validated,
    b.validationFailed,
    b.fringe.toList,
    b.fringeStateHash,
    b.memberOfFringe.map(_.toByteString).getOrElse(ByteString.EMPTY),
    b.view.seen.toList.map { case (v, r) => ViewProto(v, r.start.toLong, r.end.toLong) }
  )

  def fromBytes(bytes: Array[Byte]): BlockMetadata =
    from(BlockMetadataProto.parseFrom(bytes))

  def toBytes(b: BlockMetadata) = BlockMetadata.toProto(b).toByteArray

  // TODO consider default values to be put into BlockMessage to make LFS being reconstructed only from blocks feasible
  def fromBlock(b: BlockMessage): BlockMetadata =
    BlockMetadata(
      b.blockHash,
      b.blockNumber,
      b.sender,
      b.seqNum,
      b.justifications.toSet,
      b.bonds,
      validated = false,
      validationFailed = false,
      fringe = Set(),
      fringeStateHash = RadixHistory.emptyRootHash.toByteString,
      memberOfFringe = none,
      view = b.view
    )
}
