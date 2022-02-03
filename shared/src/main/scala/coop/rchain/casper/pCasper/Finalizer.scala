package coop.rchain.casper.pCasper
import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.casper.pCasper.Fringe.Fringe

/**
  * Finalizer searches for the next fringe that can be finalizer (maybe provisionally).
  * @param view       view of the message replayed
  * @param curFringe  current provisional fringe
  */
final case class Finalizer[F[_]: Sync, M, S](view: Map[S, M], curFringe: Fringe[M, S]) {

  /** Outputs the fringe that can be finalized. If fringe is not final, it is a provisional finalization. */
  def run(
      witnessesF: M => F[Map[S, M]],     // lowest messages from all senders that have input in the view
      justificationsF: M => F[Map[S, M]] // justifications of the message
  )(seqNum: M => Long, sender: M => S): F[Option[Fringe[M, S]]] = {

    // Witnesses that are in the view
    val witnessesInViewF = witnessesF(_: M).map(_.filter {
      case (s, m) =>
        val latestInView = view.get(s)
        assert(
          latestInView.isDefined,
          "Sender is not present in latest messages defining the view."
        )
        seqNum(m) <= seqNum(latestInView.get)
    }.toMap)

    // Targets for finalization
    val targetsF = curFringe.toList
      .traverse { case (s, m) => witnessesInViewF(m).map(_.find { case (s1, _) => s1 == s }) }
      .map(_.flatten)

    // Check whether the message is safe
    val check = SafetyOracle.run[F, M, S](_: M)(witnessesInViewF, justificationsF)(sender)

    targetsF
      .flatMap(_.traverse { case v @ (_, m) => check(m).map(v -> _) })
      .map(_.collect { case (v, Some(partition)) => v -> partition })
      .map { result =>
        assert(
          result.map { case ((_, message), _) => message }.distinct.size == result.size,
          s"Finalizer should output only unique messages but result is: \n $result."
        )
        assert(
          result.map { case ((_, _), partition) => partition }.distinct.size <= 1,
          s"Finalizer output messages have to belong to a single partition but result is: \n $result"
        )
        assert(
          result.headOption.forall {
            case (_, partition) =>
              val safeSenders = result.map { case ((sender, _), _) => sender }.toSet
              safeSenders == partition
          },
          s"There have exactly one safe message for each sender in a partition but result is: \n $result"
        )
        val r = result.map(_._1).toMap
        if (r.isEmpty) none[Fringe[M, S]] else r.some
      }
  }
}
