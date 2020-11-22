package coop.rchain.casper.engine

import cats.effect.Concurrent
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper._
import coop.rchain.casper.protocol._
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.catscontrib.ski._
import coop.rchain.comm.rp.ProtocolHelper._
import coop.rchain.crypto.hash.Blake2b256
import coop.rchain.crypto.signatures.Secp256k1
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.shared.{Cell, EventPublisher}
import fs2.concurrent.Queue
import monix.eval.Task
import org.scalatest.{BeforeAndAfterEach, WordSpec}

import scala.concurrent.duration._

class InitializingSpec extends WordSpec with BeforeAndAfterEach {
  implicit val eventBus = EventPublisher.noop[Task]

  val fixture = Setup()
  import fixture._

  override def beforeEach(): Unit =
    transportLayer.setResponses(_ => p => Right(()))

  override def afterEach(): Unit =
    transportLayer.reset()

  "Initializing state" should {
    "make a transition to Running once ApprovedBlock has been received" in {
      import monix.execution.Scheduler.Implicits.global

      val theInit = Task.unit

      implicit val engineCell = Cell.unsafe[Task, Engine[Task]](Engine.noop)

      val blockResponseQueue = Queue.unbounded[Task, BlockMessage].runSyncUnsafe()
      val stateResponseQueue = Queue.unbounded[Task, StoreItemsMessage].runSyncUnsafe()

      // interval and duration don't really matter since we don't require and signs from validators
      val initializingEngine =
        new Initializing[Task](
          fixture.blockProcessingQueue,
          fixture.blockProcessingState,
          fixture.casperShardConf,
          Some(validatorId),
          theInit,
          blockResponseQueue,
          stateResponseQueue,
          trimState = false,
          enableStateExporter = true
        )

      val approvedBlockCandidate = ApprovedBlockCandidate(block = genesis, requiredSigs = 0)

      val approvedBlock: ApprovedBlock = ApprovedBlock(
        candidate = approvedBlockCandidate,
        sigs = List(
          Signature(
            ByteString.copyFrom(validatorPk.bytes),
            "secp256k1",
            ByteString.copyFrom(
              Secp256k1
                .sign(Blake2b256.hash(approvedBlockCandidate.toProto.toByteArray), validatorSk)
            )
          )
        )
      )

      val postStateHashBs = approvedBlock.candidate.block.body.state.postStateHash
      val postStateHash   = Blake2b256Hash.fromByteString(postStateHashBs)
      val startPath       = Seq((postStateHash, none))

      // Store request message
      val storeRequestMessage =
        StoreItemsMessageRequest(startPath, 0, LastFinalizedStateTupleSpaceRequester.pageSize)
      // Store response message
      val storeResponseMessage =
        StoreItemsMessage(startPath = startPath, lastPath = startPath, Seq(), Seq())
      // Block request message
      val blockRequestMessage = BlockRequest(genesis.blockHash)

      // Send two response messages to signal the end
      val enqueueResponses = stateResponseQueue.enqueue1(storeResponseMessage) *>
        stateResponseQueue.enqueue1(storeResponseMessage) *>
        // Send block response
        blockResponseQueue.enqueue1(genesis)

      val expectedRequests = Seq(
        packet(local, networkId, storeRequestMessage.toProto),
        packet(local, networkId, blockRequestMessage.toProto),
        packet(local, networkId, ForkChoiceTipRequestProto())
      )

      val test = for {
        _ <- EngineCell[Task].set(initializingEngine)

        // Send responses with some delay because `Initializing.handle` is blocking until LFS is not received.
        _ <- Concurrent[Task].start(Task.sleep(5.second) >> enqueueResponses)

        // Handle approved block (it's blocking until responses are received)
        _ <- initializingEngine.handle(local, approvedBlock)

        engine          <- EngineCell[Task].read
        casperDefined   <- engine.withCasper(kp(true.pure[Task]), false.pure[Task])
        _               = assert(casperDefined)
        blockO          <- blockStore.get(genesis.blockHash)
        _               = assert(blockO.isDefined)
        _               = assert(blockO.contains(genesis))
        handlerInternal <- EngineCell[Task].read
        _               = assert(handlerInternal.isInstanceOf[Running[Task]])

        // Assert requested messages for the state and fork choice tip
        _        = assert(transportLayer.requests.size == expectedRequests.size)
        messages = transportLayer.requests.map(_.msg)
        _        = assert(messages.toSet == expectedRequests.toSet)
        _        = transportLayer.reset()

        // assert that we really serve last approved block
        lastApprovedBlockO <- LastApprovedBlock[Task].get
        _                  = assert(lastApprovedBlockO.isDefined)
        _ <- EngineCell[Task].read >>= (_.handle(
              local,
              ApprovedBlockRequest("test", trimState = false)
            ))
        // TODO: fix missing signature in received approved block
        _ = assert(
          transportLayer.requests
            .exists(_.msg.message.packet.get.content == approvedBlock.toProto.toByteString)
        )
      } yield ()

      test.unsafeRunSync
    }
  }

}
