package coop.rchain.casper.pCasper
import cats.Show
import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.casper.pCasper.Fringe.{isFinal, Fringe, Reconciler}

object PCasper {

  /**
    * Compute what message sees as a finalization fringe.
    * @param justifications view of the message
    * @param parents parents are subset of justifications that do not reference other justificatins
    * @param reconciler instance of [[Reconciler]] to merge finality views of parents
    * @param recordFinalFringe during computation of the view a final fringe can be found, which should be recorded.
    * @param bonds full bonds map
    * @param Final read finality view of the message
    * @param witnessesF first messages from all senders that have input in a view (as ancestor)
    * @param justificationsF read justifications
    * @param seqNum sequence number of a message
    * @param sender sender of a message
    *
    * @return Total fringe as per message view.
    */
  def computeFinalityView[F[_]: Sync, M, S: Show](
      justifications: Map[S, M],
      parents: List[M],
      reconciler: Reconciler[F, M, S],
      recordFinalFringe: Fringe[M, S] => F[Unit],
      bonds: Map[S, Long]
  )(
      Final: M => Fringe[M, S],
      seqNum: M => Long,
      sender: M => S,
      witnessesF: M => F[Map[S, M]],
      justificationsF: M => F[Map[S, M]]
  ): F[Fringe[M, S]] = {

    val reconcileParentViewsF = {
      val toReconcile = parents.map(Final).distinct
      if (toReconcile.size == 1)
        toReconcile.head.pure // nothing to reconcile, all parents are from the same partition
      else {
        val parentPartitions = toReconcile.map(_.keySet).distinct
        val unexpectedPartitionsOpt = parentPartitions.combinations(2).find { pair =>
          val l     = pair.head
          val r     = pair.last
          val valid = (l intersect r).isEmpty || (l -- r).isEmpty || (r -- l).isEmpty
          !valid
        }
        unexpectedPartitionsOpt.foreach {
          case List(l, r) =>
            assert(
              assertion = false,
              s"Unexpected parent partitions detected: ${l.map(_.show)} ${r.map(_.show)}.\n" +
                s"Parent partitions are expected to be non overlapping if one does not contain another in full."
            )
        }
        reconciler.reconcile(toReconcile)
      }
    }

    for {
      // 1. Reconcile views of parents
      parentsView <- reconcileParentViewsF
      // 2. Advance finalization bringing parents into the scope
      r <- Finalizer(justifications, parentsView).run(witnessesF, justificationsF)(seqNum, sender)
      // If fringe advancement in a supermajority partition found, record finalization
      _ <- r.traverse(fringe => recordFinalFringe(fringe).whenA(isFinal(fringe)(bonds)))
    } yield r.getOrElse(parentsView)
  }
}
