package coop.rchain.casper.pCasper
import cats.Show
import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.casper.pCasper.Finalizer.FinalityDecision
import coop.rchain.casper.pCasper.Fringe.{Fringe, Reconciler}

object PCasper {

  type FinalDecisions[M]       = List[List[M]]
  type ProvisionalDecisions[M] = List[List[M]]

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
      bonds: Map[S, Long],
      witnessesF: M => F[Map[S, M]],
      justificationsF: M => F[Map[S, M]]
  )(
      Final: M => F[Fringe[M, S]],
      seqNum: M => Long,
      sender: M => S
  ): F[(Fringe[M, S], FinalDecisions[M], ProvisionalDecisions[M])] = {

    val totalStake = bonds.valuesIterator.sum

    def reconcileParentViewsF(toReconcile: List[Fringe[M, S]]): F[Fringe[M, S]] =
      if (toReconcile.size == 1)
        toReconcile.head.pure
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

    for {
      // 1. Reconcile views of parents.
      parentsView <- parents.traverse(Final).map(_.distinct).flatMap(reconcileParentViewsF)
      // 2. Advance finalization bringing parents into the scope
      r <- Finalizer(justifications, parentsView).run(witnessesF, justificationsF)(seqNum, sender)
    } yield {
      // Finality decisions grouped by partitions, sorted by partition stake from highest stake to lowest
      val toMerge = r.toList
        .filter { case (_, FinalityDecision(_, partition)) => partition.size > 1 }
        .groupBy { case (_, FinalityDecision(_, partition)) => partition }
        .map { case (partition, v) => partition.toIterator.map(bonds).sum -> v }
      // Messages that should be merged into final state
      // Messages between which conflict resolution can be done.
      val (finalDecisions, provisionalDecisions) = toMerge.partition {
        case (partitionStake, _) => partitionStake * 3 > totalStake * 2
      }
      val superPartitions = finalDecisions.flatMap {
        case (_, v) => v.map { case (_, FinalityDecision(_, partition)) => partition }
      }
      assert(
        superPartitions.toList.distinct.size <= 1,
        s"Overlapping supermajority partitions detected ${finalDecisions}."
      )
      // New finalization fringe (final + provisional)
      val newTotalFringe = parentsView ++ toMerge.flatMap {
        case (_, v) => v.map { case (s, d) => s -> d.msg }
      }
      (
        newTotalFringe,
        finalDecisions.map { case (_, v)       => v.map { case (_, FinalityDecision(m, _)) => m } }.toList,
        provisionalDecisions.map { case (_, v) => v.map { case (_, FinalityDecision(m, _)) => m } }.toList
      )
    }
  }
}
