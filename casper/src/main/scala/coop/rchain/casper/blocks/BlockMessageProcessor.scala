package coop.rchain.casper.blocks
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.syntax.all._
import coop.rchain.blockstorage.dag.BlockDagRepresentation
import coop.rchain.blockstorage.state.CasperStateValidated
import coop.rchain.casper.BlockStatus._
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.state.{CasperState, CasperStateManager}
import coop.rchain.models.BlockHash._
import coop.rchain.models.block.StateHash.StateHash
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import coop.rchain.state.StateManager
import fs2.{Pipe, Stream}
import fs2.concurrent.Queue

import scala.collection.immutable.Set

object BlockMessageProcessor {

  /** Casper message processor inputs blocks and outputs the Casper states. */
  def apply[F[_]: Concurrent: Log](
      casperStateManager: CasperStateManager[F],
      // functions required
      checkFormatF: BlockMessage => Option[IgnoreReason],
      storeF: BlockMessage => F[Unit],
      readF: BlockHash => F[BlockMessage],
      validateF: (BlockMessage, CasperStateValidated) => F[Option[Offence]],
      // effects invoked
      requestDependenciesF: Set[BlockHash] => F[Unit],
      validationEffectF: (BlockMessage, Option[Offence]) => F[Option[BlockDagRepresentation[F]]]
  ): Pipe[F, BlockMessage, CasperStateValidated] = {

    def logIgnored(r: IgnoreReason): F[Unit] = Log[F].debug(s"Incoming block ignored ($r)")

    // Resources required to create stream
    val mkReqs = for {
      validationQueue <- Queue.unbounded[F, BlockMessage]
      casperStateRef  = casperStateManager.getStateRef

    } yield (validationQueue, casperStateRef)

    def p(input: Stream[F, BlockMessage]): Stream[F, CasperStateValidated] =
      Stream.eval(mkReqs).flatMap {
        case (vQueue, casperStRef) =>
          /** Drop messages that Casper is not interested in. */
          def dropIgnorableF(m: BlockMessage): F[Either[IgnoreReason, BlockMessage]] = {
            val checkNotOfInterestF =
              casperStRef.get
                .map(s => (s.known(m.blockHash), s.beforeFinalized(m.body.state.blockNumber)))
                .map {
                  case (true, _)      => Left(Known)
                  case (false, true)  => Left(Old)
                  case (false, false) => Right(m)
                }
            checkNotOfInterestF.map(_ => Either.fromOption(checkFormatF(m), m).swap)
          }

          /** Record new message received and available for processing. */
          def casperAcceptNewF(m: BlockMessage): F[(BlockMessage, Boolean, Set[BlockHash])] =
            casperStRef.modify { st =>
              val dependencies = m.justifications.map(_.latestBlockHash)
              val (newSt, isReady, _, toRequest) =
                st.recordReceived(m.blockHash, dependencies.toSet)
              (newSt, (m, isReady, toRequest))
            }

          // Stream routing messages received from network to processing queue
          val pullIncoming = input
            .parEvalMapProcBounded { dropIgnorableF }
            .evalMapFilter {
              case Left(reason) => logIgnored(reason).as(none[BlockMessage])
              case Right(m)     => m.some.pure
            }
            .evalTap { storeF }
            // record new message to Casper state
            .evalMap { casperAcceptNewF }
            .evalMapFilter {
              // request missing dependencies if any
              case (_, false, mis) if mis.nonEmpty =>
                requestDependenciesF(mis).as(none[BlockMessage])
              // send to validation if dependency free
              case (m, true, _) => m.some.pure
            }
            .through { vQueue.enqueue }

          validate concurrently pullIncoming
      }

    (input: Stream[F, BlockMessage]) => p(input)
  }
}
