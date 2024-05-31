package coop.rchain.casper.engine

import cats.effect.Async
import cats.syntax.all._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.BlockStore.BlockStore
import coop.rchain.blockstorage.approvedStore.ApprovedStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.casper._
import coop.rchain.casper.protocol.{CommUtil, _}
import coop.rchain.casper.rholang.RuntimeManager
import coop.rchain.casper.syntax._
import coop.rchain.comm.PeerNode
import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import coop.rchain.comm.transport.TransportLayer
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.rspace.state.{RSpaceImporter, RSpaceStateManager}
import coop.rchain.shared._
import coop.rchain.shared.syntax._
import fs2.concurrent.Channel

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import cats.effect.{Deferred, Ref, Temporal}
import coop.rchain.models.Validator.Validator
import coop.rchain.models.syntax.modelsSyntaxByteString
import coop.rchain.rholang.interpreter.merging.RholangMergingLogic.{
  codecMergeableKey,
  DeployMergeableData,
  NumberChannel
}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.sdk.dag.View
import scodec.bits.ByteVector

object NodeSyncing {

  /**
    * Represents start of the node with creation of genesis block.
    */
  // format: off
  def apply[F[_]
  /* Execution */   : Async
  /* Transport */   : TransportLayer: CommUtil
  /* State */       : RPConfAsk: ConnectionsCell
  /* Rholang */     : RuntimeManager
  /* Storage */     : BlockStore: ApprovedStore: BlockDagStorage: RSpaceStateManager
  /* Diagnostics */ : Log: Metrics: Span] // format: on
  (
      finished: Deferred[F, Unit],
      validatorId: Option[ValidatorIdentity],
      trimState: Boolean = true
  ): F[NodeSyncing[F]] =
    for {
      incomingBlocksQueue <- Channel.bounded[F, BlockMessage](50)
      stateResponseQueue  <- Channel.bounded[F, StoreItemsMessage](50)
      engine = new NodeSyncing(
        finished,
        incomingBlocksQueue,
        validatorId,
        stateResponseQueue,
        trimState
      )
    } yield engine

}

/**
  * Represents start of the node with creation of genesis block.
  */
// format: off
class NodeSyncing[F[_]
  /* Execution */   : Async
  /* Transport */   : TransportLayer: CommUtil
  /* State */       : RPConfAsk: ConnectionsCell
  /* Rholang */     : RuntimeManager
  /* Storage */     : BlockStore: ApprovedStore: BlockDagStorage: RSpaceStateManager
  /* Diagnostics */ : Log: Metrics: Span] // format: on
(
    finished: Deferred[F, Unit],
    incomingBlocksQueue: Channel[F, BlockMessage],
    validatorId: Option[ValidatorIdentity],
    tupleSpaceQueue: Channel[F, StoreItemsMessage],
    trimState: Boolean = true
) {
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def handle(peer: PeerNode, msg: CasperMessage): F[Unit] = msg match {
    case ab: BootstrapDataMessage =>
      onFinalizedFringeMessage(peer, ab)

    case s: StoreItemsMessage =>
      Log[F].info(s"Received ${s.pretty} from $peer.") *>
        tupleSpaceQueue
          .trySend(s)
          .map(
            _.leftTraverse(
              _ => new Exception("Channel received store item is closed").raiseError[F, Unit]
            ).map(_.merge)
          )

    case b: BlockMessage =>
      Log[F]
        .info(s"BlockMessage received ${PrettyPrinter.buildString(b, short = true)} from $peer.") *>
        incomingBlocksQueue
          .trySend(b)
          .map(
            _.leftTraverse(
              _ => new Exception("Channel received block message is closed").raiseError[F, Unit]
            ).map(_.merge)
          ) *>
        // Save mergeable data
        {
          // Key is composed from post-state hash and block creator with seq number
          val key = (
            b.postStateHash.toBlake2b256Hash.bytes,
            ByteVector(b.sender.toByteArray),
            b.seqNum
          )
          val keyEncoded = codecMergeableKey.encode(key).require.toByteVector
          val deployChannels = b.mergeables.map { v =>
            DeployMergeableData(v.map { case (c, d) => NumberChannel(c, d) }.toSeq)
          }
          RuntimeManager[F].getMergeableStore.put(keyEncoded, deployChannels) >> Log[F].info(
            s"Filled ${b.postStateHash.toHexString}, ${keyEncoded.toHex}"
          )
        }

    case _ => ().pure
  }

  // TEMP: flag for single call for process approved block
  val startRequester = Ref.unsafe(true)

  private def onFinalizedFringeMessage(
      sender: PeerNode,
      msg: BootstrapDataMessage
  ): F[Unit] = {
    val senderIsBootstrap = RPConfAsk[F].ask.map(_.bootstrap.exists(_ == sender))

    def handleApprovedBlock = {
      val fringeLogMsg = s"Received bootstrap data ${msg.show}."
      for {
        _ <- Log[F].info(fringeLogMsg)

        // Download approved state and all related blocks
        _ <- requestApprovedState(
              msg.tips.toSet,
              msg.lowerBound.map(x => x.validator -> x.seqNum).toMap,
              msg.finalStateHash.toBlake2b256Hash
            )

        // Approved block is saved after the whole state is received,
        //  to restart requesting if interrupted with incomplete state.
//        _ <- ApprovedStore[F].putApprovedBlock(msg.finalFringeMsg)

        _ <- Log[F].info(
              s"LFS state for tips ${msg.tips.map(_.toHexString.take(8))} is successfully restored."
            )
      } yield ()
    }

    for {
      isValid <- senderIsBootstrap

      _ <- Log[F].info("Fringe message ignored, not received from bootstrap node.").whenA(!isValid)

      // Start only once, when state is true and approved block is valid
      start <- startRequester.modify {
                case true if isValid  => (false, true)
                case true if !isValid => (true, false)
                case _                => (false, false)
              }

      _ <- handleApprovedBlock.whenA(start)
    } yield ()
  }

  def requestStates(states: List[Blake2b256Hash]): F[Unit] = {
    // Request tuple space state for Last Finalized State
    val stateValidator = RSpaceImporter.validateStateItems[F] _

    val stream = LfsTupleSpaceRequester.stream(
      states,
      tupleSpaceQueue,
      (statePartPath, pageSize) =>
        TransportLayer[F].sendToBootstrap(
          StoreItemsMessageRequest(statePartPath, 0, pageSize).toProto
        ),
      requestTimeout = 10.seconds,
      RSpaceStateManager[F].importer,
      stateValidator
    )

    Log[F].info(s"Loading ${states.size} states") *> stream.flatMap(_.compile.drain)
  }

  def requestApprovedState(
      lms: Set[BlockHash],
      edge: Map[Validator, Long],
      finalStateHash: Blake2b256Hash
  ): F[Unit] =
    for {
      // Request all blocks for Last Finalized State
      blockRequestStream <- LfsBlockRequester.stream(
                             lms,
                             edge,
                             incomingBlocksQueue.stream,
                             MultiParentCasper.deployLifespan,
                             hash => CommUtil[F].broadcastRequestForBlock(hash, 1.some),
                             requestTimeout = 3.seconds,
                             BlockStore[F].contains(_),
                             BlockStore[F].getUnsafe,
                             BlockStore[F].put(_, _),
                             Validate.blockHash[F]
                           )

      // Receive the blocks and after populate the DAG
      blockRequestAddDagStream = blockRequestStream.last.unNoneTerminate.evalMap { st =>
        populateDag(st.heightMap) *>
          Log[F].info(s"Blocks for LFS received and added to the state.") *>
          fs2.Stream
            .emits(st.heightMap.values.flatten.toList.distinct)
            .evalMap(
              BlockStore[F]
                .getUnsafe(_)
                .map(b => List(b.preStateHash, b.postStateHash))
            )
            .flatMap(fs2.Stream.emits)
            .map(_.toBlake2b256Hash)
            .compile
            .to(Set)
            .flatMap(x => requestStates(finalStateHash +: x.toList)) *>
          Log[F].info(s"States for LFS received and imported.")
      }
      _ <- blockRequestAddDagStream.compile.drain

      // Mark finished initialization
      _ <- finished.complete(())
    } yield ()

  private def populateDag(
      heightMap: SortedMap[Long, Set[BlockHash]]
  ): F[Unit] = {
    def addBlockToDag(block: BlockMessage): F[Unit] =
      for {
        _ <- Log[F].info(
              s"Adding ${PrettyPrinter.buildString(block, short = true)}."
            )
        bmd = BlockMetadata.fromBlock(block)
        _   <- BlockDagStorage[F].insert(bmd, block, isSync = true)
      } yield ()

    for {
      _ <- Log[F].info(s"Adding blocks for approved state to DAG.")

      // TODO: height map cannot be used here because invalid blocks can have
      //  invalid block number which will break sequence in height map
      // Add sorted DAG in order from approved block to oldest
      _ <- heightMap.flatMap(_._2).toList.traverse_ { hash =>
            for {
              block <- BlockStore[F].getUnsafe(hash)
              // TODO: blocks added to DAG without validation will have flag `processed=false` so invalid flag is not applicable
              // If sender has stake 0 in approved block, this means that sender has been slashed and block is invalid
              // Filter older not necessary blocks
              // blockHeight = block.blockNumber
              // blockHeightOk = blockHeight >= minHeight
              // Add block to DAG
              _ <- addBlockToDag(block) //.whenA(blockHeightOk)
            } yield ()
          }

      _ <- Log[F].info(s"Blocks for approved state added to DAG.")
    } yield ()
  }

}
