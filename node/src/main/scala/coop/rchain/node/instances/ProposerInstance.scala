package coop.rchain.node.instances

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import coop.rchain.casper.Casper
import coop.rchain.casper.blocks.proposer.{BugError, ProposeResult, Proposer}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.state.instances.ProposerState
import coop.rchain.shared.Log
import cats.syntax.all._
import fs2.Stream
import fs2.concurrent.Queue

object ProposerInstance {
  def create[F[_]: Concurrent: Log](
      proposeRequestsQueue: Queue[F, (Casper[F], Deferred[F, Option[Int]])],
      proposer: Proposer[F],
      state: Ref[F, ProposerState[F]]
  ): Stream[F, (ProposeResult, Option[BlockMessage])] = {
    // stream of requests to propose
    val in = proposeRequestsQueue.dequeue

    // max number of concurrent attempts to propose. Actual propose can happen only one at a time, but clients
    // are free to make propose attempt. In that case proposeID returned will be None.
    val maxConcurrentAttempts = 100

    val out = Stream
    // propose permit
      .eval(Semaphore[F](1))
      .flatMap { lock =>
        in.map { i =>
            val (c, proposeIDDef) = i

            Stream
              .eval(lock.tryAcquire)
              // if propose is in progress - resolve proposeID to None and stop here
              .evalFilter { v =>
                proposeIDDef.complete(None).unlessA(v).as(v)
              }
              // execute propose
              .evalMap { _ =>
                for {
                  _ <- Log[F].info("Propose started")
                  // deferred for new propose result, update state
                  rDef <- Deferred[F, (ProposeResult, Option[BlockMessage])]
                  _ <- state
                        .update { s =>
                          s.copy(currProposeResult = rDef.some)
                        }
                  r <- proposer.propose(c, proposeIDDef)
                  // complete deferred with propose result, update state
                  _ <- rDef.complete(r)
                  _ <- state
                        .update { s =>
                          s.copy(latestProposeResult = r.some, currProposeResult = None)
                        }
                  _ <- lock.release
                  _ <- Log[F].info(s"Propose finished: ${r._1.proposeStatus}")
                } yield r
              }
          }
          .parJoin(maxConcurrentAttempts)
      }

    out
  }
}
