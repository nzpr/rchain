package coop.rchain.casper.blocks

import cats.Show
import cats.effect.{Async, Sync}
import cats.syntax.all._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.BlockStore.BlockStore
import coop.rchain.blockstorage.dag.{BlockDagStorage, DagRepresentation}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.syntax._
import coop.rchain.casper.{PrettyPrinter, Validate}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import fs2.Stream
import fs2.concurrent.Channel
import cats.effect.Ref
import coop.rchain.models.syntax.modelsSyntaxByteString

sealed trait RecvStatus
// Begin checking and storing block
case object BeginStoreBlock extends RecvStatus
// Block stored in the block store, waiting for validation and DAG insertion
case object EndStoreBlock extends RecvStatus
// Block sent to validation
case object PendingValidation extends RecvStatus
// Requested missing dependencies
case object Requested extends RecvStatus

object BlockReceiverState {
  def apply[MId: Show]: BlockReceiverState[MId] = BlockReceiverState(
    blocksSt = Map(),
    receiveSt = Map(),
    childRelations = Map()
  )
}

/**
  * Block receiver state
  *
  * It consist of three events. Two to store blocks (begin and end) to prevent race when
  * storing blocks and finished when block is validated and added to the DAG (end of processing).
  */
final case class BlockReceiverState[MId: Show] private (
    /**
      * Blocks received and stored in BlockStore (not validated) with parent relations
      */
    blocksSt: Map[MId, Set[MId]],
    /**
      * Blocks receiving status
      */
    receiveSt: Map[MId, RecvStatus],
    /**
      * Blocks mapping with children relations
      */
    childRelations: Map[MId, Set[MId]]
) {

  /**
    * Begin storing block, mark block to prevent duplicate threads store the same block
    *  - thread safe sync like opening transaction for a block by block hash
    */
  def beginStored(id: MId): (BlockReceiverState[MId], Boolean) = {
    val expectedReceive =
      receiveSt.get(id).collect { case Requested => true; case _ => false }.getOrElse(true)
    if (expectedReceive) {
      // Update state to begin received status
      val newReceiveSt = receiveSt + ((id, BeginStoreBlock))
      (copy(receiveSt = newReceiveSt), true)
    } else {
      (this, false)
    }
  }

  /**
    * Storing of block done, waiting validation
    *  - like closing transaction, block stored and waiting validation
    *
    *  @return toRequest parent dependencies that will be requested
    */
  def endStored(id: MId, lockingDeps: Set[MId]): (BlockReceiverState[MId], Set[MId]) = {
    val curStateOpt = receiveSt.get(id)
    assert(
      curStateOpt == BeginStoreBlock.some,
      s"EndStored should be called only in begin stored state, actual: $curStateOpt, hash: $id"
    )
    curStateOpt
      .collect {
        case BeginStoreBlock =>
          // Update blocks state, keep unseen parents only
          val newBlocksSt = blocksSt + ((id, lockingDeps))

          // Update block status to received and set unseen parents to Pending receive state
          val newReceiveStored  = receiveSt + ((id, EndStoreBlock))
          val newPendingReceive = (lockingDeps -- receiveSt.keys).map((_, Requested))
          val newReceiveSt      = newReceiveStored ++ newPendingReceive

          // Update children relations of received block
          val newChildRelations = lockingDeps.foldLeft(childRelations) {
            case (acc, parent) =>
              val childs = acc.getOrElse(parent, Set())
              acc + ((parent, childs + id))
          }

          // New state
          val newState = copy(
            blocksSt = newBlocksSt,
            receiveSt = newReceiveSt,
            childRelations = newChildRelations
          )

          (newState, lockingDeps)
      }
      // TODO: this should never happen, protected by assert
      //  (maybe we need helper function to wrap the whole pattern or return error to caller (effect))
      .getOrElse((this, Set()))
  }

  /**
    * Finished block validation, update state and return next blocks
    *
    * @return next blocks with validated dependencies
    */
  def finished(id: MId, parents: Set[MId]): (BlockReceiverState[MId], Set[MId]) = {
    val inState = blocksSt.contains(id)
    val isReceived = receiveSt
      .get(id)
      .collect {
        case EndStoreBlock     =>
        case PendingValidation =>
      }
      .isDefined
    // To finish block it must be present in the state (parents relations and at least stored)
    assert(
      inState && isReceived,
      s"Calling finished on unexpected block hash ${id.show} (inState $inState isReceived $isReceived)."
    )

    // Update blocks state
    //  - remove finished block from child dependencies and remove finished block
    //  - remove finished block from blocks state
    val childs        = childRelations.get(id).toList.flatten
    val updatedBlocks = childs.map(b => (b, blocksSt(b) - id)).toMap
    val newBlocksSt   = blocksSt ++ updatedBlocks - id

    // Get next blocks with all dependencies validated and not already in pending validation state
    val depsValidated = updatedBlocks.filter {
      case (bid, parents) =>
        def pending = receiveSt.get(bid).contains(PendingValidation)
        parents.isEmpty && !pending
    }.keySet

    // Update received state
    //  - set to pending validation state
    //  - remove finished block from received state
    val depsValidatedPending = depsValidated.map((_, PendingValidation))
    val newReceiveSt         = receiveSt ++ depsValidatedPending - id

    // Remove finished block from children relations
    val newChildRelations = parents.foldLeft(childRelations) {
      case (acc, parent) =>
        val childs = acc.getOrElse(parent, Set()) - id
        if (childs.isEmpty) acc - parent
        else acc + ((parent, childs))
    }

    // New state
    val newState = copy(
      blocksSt = newBlocksSt,
      receiveSt = newReceiveSt,
      childRelations = newChildRelations
    )

    (newState, depsValidated)
  }
}

object BlockReceiver {
  def apply[F[_]: Async: BlockStore: BlockDagStorage: BlockRetriever: Log](
      state: Ref[F, BlockReceiverState[BlockHash]],
      incomingStream: Stream[F, BlockMessage],
      validatedStream: Stream[F, BlockMessage],
      confShardName: String,
      receive: BlockMessage => F[Unit] // loopback call to pass block to the input of the receiver
  ): F[Stream[F, BlockHash]] = {

    def blockStr(b: BlockMessage) = PrettyPrinter.buildString(b, short = true)
    def logNotOfInterest(b: BlockMessage) =
      Log[F].info(s"Block ${blockStr(b)} is not of interest. Dropped")
    def logMalformed(b: BlockMessage) =
      Log[F].info(s"Block ${blockStr(b)} is malformed. Dropped")

    // TODO: add logging of missing dependencies
    // def logMissingDeps(b: BlockMessage) = Log[F].info(s"Block ${blockStr(b)} missing dependencies.")

    // Check if input string is equal to configuration shard ID
    def checkIfEqualToConfigShardId(shardId: String) = {
      val isValid = confShardName == shardId
      def logMsg  = s"Ignored block with invalid shard, expected: $confShardName, received: $shardId"
      Log[F].info(logMsg).whenA(!isValid).as(isValid)
    }

    // Check if block data is cryptographically safe and part of the same shard
    def checkIfOfInterest(b: BlockMessage): F[Boolean] = Sync[F].defer {
      val validShard = checkIfEqualToConfigShardId(b.shardId)
      // TODO: 1. validation and logging in these checks should be separated
      //       2. logging of these block should indicate that information cannot be trusted
      //           e.g. if block hash is invalid it cannot represent identity of a block
      val validFormat = Validate.formatOfFields(b)
      val validHash   = Validate.blockHash(b)
      val validSig    = Validate.blockSignature(b)
      // TODO: check sender to be valid bonded validator
      //  - not always possible because now are new blocks downloaded from DAG tips
      //    which in case of epoch change sender can be unknown
      // TODO: check valid version (possibly part of hash checking)
      validFormat &&^ validShard &&^ validHash &&^ validSig
    }

    // Check if block should be stored
//    def checkIfKnown(b: BlockMessage, dag: DagRepresentation): F[Boolean] =
//      Sync[F].delay(dag.contains(b.blockHash))
    //dag.heightMap.headOption.map(_._1).getOrElse(-1L) > b.blockNumber

    def requestMissingDependencies(deps: Seq[BlockHash]): F[Unit] =
      deps.traverse_(
        BlockRetriever[F].admitHash(_, admitHashReason = BlockRetriever.MissingDependencyRequested)
      )

    implicit val hashShow: Show[BlockHash] = Show.show[BlockHash](_.toHexString)

    // Process incoming blocks
    def incomingBlocks(receiverOutputQueue: Channel[F, BlockHash]) =
      incomingStream
        .evalFilterAsyncUnorderedProcBounded { block =>
          // Filter (ignore) blocks that are not of interest (pass integrity check, incorrect shard or version, ...)
          checkIfOfInterest(block).flatTap(logMalformed(block).unlessA(_))
        }
        .parEvalMapUnorderedProcBounded { block =>
          // Save block to store, mark end of checking in the state (end received "transaction")
          def markReceivedAndStore: F[Unit] =
            for {
              // Save block to block store
              // notify BlockRetriever that block is received
              blockStored <- BlockStore[F].contains(block.blockHash)
              _ <- (BlockStore[F].put(block) *> BlockRetriever[F].ackReceived(block.blockHash))
                    .unlessA(blockStored)

              parents = block.justifications

              // parents to request
              toRequest <- BlockStore[F].getMissing(parents).flatTap(requestMissingDependencies)

              // get latest validated state
              dag <- BlockDagStorage[F].getRepresentation

              validated = dag.contains(block.blockHash)

              // add block to state specifying locking dependencies
              lockingDependencies = parents.toSet -- dag.dagSet
              _                   <- state.modify(_.endStored(block.blockHash, lockingDependencies))

              // dangling dependencies are sent back to the input of the receiver since they are stored already
              // but not in the dag
              danglingDependencies = lockingDependencies -- toRequest
              _ <- BlockStore[F]
                    .get(danglingDependencies.toList)
                    .flatMap(_.flatten.traverse(receive))

              // send hash that has no locking dependencies to the output
              _ <- receiverOutputQueue
                    .trySend(block.blockHash)
                    .whenA(lockingDependencies.isEmpty && !validated)
            } yield ()

          state.modify(_.beginStored(block.blockHash)).ifM(markReceivedAndStore, ().pure)
        }

    // Process validated blocks
    def validatedBlocks(receiverOutputQueue: Channel[F, BlockHash]) =
      validatedStream.parEvalMapUnorderedProcBounded { block =>
        val parents = block.justifications.toSet
        for {
          // Update state with finalized block and get next for validation
          next <- state.modify(_.finished(block.blockHash, parents))

          // Send dependency free blocks to validation
          _ <- next.toList.traverse_(receiverOutputQueue.send)
        } yield ()
      }

    // Return output stream, in parallel process incoming and validated blocks
    Channel.unbounded[F, BlockHash].map { outQueue =>
      outQueue.stream concurrently incomingBlocks(outQueue) concurrently validatedBlocks(outQueue)
    }
  }
}
