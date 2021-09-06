package coop.rchain.casper.blocks

import cats.effect.Concurrent
import cats.syntax.all._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.casperbuffer.CasperBufferStorage
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.state.CasperStateValidated
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.state.{CasperState, CasperStateManager}
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.{CasperSnapshot, Estimator, MultiParentCasperImpl}
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.shared.syntax._
import coop.rchain.shared.{Log, Time}
import fs2.Stream
import fs2.concurrent.Queue

object Validation {
  def stream[F[_]: Concurrent: BlockDagStorage: BlockStore: CasperBufferStorage: Log: RuntimeManager: Estimator: Span: Time: Metrics](
      // stream of mes
      input: Stream[F, BlockMessage],
      // validation should always be performed using the latest casper state, available in manager
      casperStateManager: CasperStateManager[F]
  ): Stream[F, CasperStateValidated] = {

    val casperStRef = casperStateManager.getStateRef

    // Queue of messages to be processed is created here.
    // Note: this queue is used and not the input stream because successful validation of a message can trigger
    // processing of another message, which depends on the first one.
    Stream.eval(Queue.unbounded[F, BlockMessage]).flatMap { vQueue =>
      // read the latest casper state
      val validation = vQueue
        .dequeueChunk(1)
        .evalMap(b => casperStRef.get.map((b, _)))
        // prepare casper snapshot and validate, all concurrently
        .parEvalMapProcBounded {
          case (b, st) =>
            for {
              s <- CasperSnapshot(b.some, st.validatedState.some, casperStateManager.getShardConf)
              r <- MultiParentCasperImpl.validate(b, s).map((st, b, _))
            } yield r
        }
        .attempt
        .evalMap {
          // Unhandled error during validation is subject to serious investigation - node should be stopped.
          case Left(err) =>
            val errStr = s"Unexpected error during validation: $err. Please report to maintainer."
            new Exception(errStr).raiseError[F, CasperState]

          // Update casper state, stream ensures atomicity
          case Right((initSt, b, offenceOpt)) =>
            for {
              bdrOpt <- MultiParentCasperImpl.validatedEff(b, offenceOpt)
              // This update relies on some magic done by effectful BlockDagRepresetation,
              // so state is returned by validation effect TODO make it pure
              newStateOpt <- bdrOpt.traverse { bdr =>
                              casperStRef.updateAndGet(
                                _.recordValidated(b.blockHash, bdr.getPureState)
                              )
                            }
              r = newStateOpt.getOrElse(initSt)
              // if state is not changed after validation - return initial one
            } yield r
        }
        .evalTap { newSt =>
          Stream
            .fromIterator(newSt.readySet.iterator)
            .evalMap(BlockStore[F].getUnsafe(_).flatMap(vQueue.enqueue1))
            .compile
            .lastOrError
            .as(newSt)
        }
        // return new validated state
        .map(_.validatedState)

      validation concurrently input.map(vQueue.enqueue1)
    }
  }
}
