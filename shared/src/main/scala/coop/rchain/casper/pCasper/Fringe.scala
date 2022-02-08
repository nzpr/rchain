package coop.rchain.casper.pCasper
import cats.{Applicative, Id, Monad}
import cats.syntax.all._

object Fringe {

  /**
    * Finalization fringe - provisional finalization of a partition or, if senders of messages of the fringe
    * represents supermajority, boundary of irreversible state of the network.
    */
  type Fringe[M, S] = Map[S, M]

  /** Whether fringe is provisional (can be modified on reconciliation). */
  def isFinal[M, S](fringe: Fringe[M, S])(bonds: Map[S, Long]) = {
    val fringeSenders = fringe.keySet
    require(
      (fringeSenders -- bonds.keySet).isEmpty,
      "Input fringe has messages from not bonded senders."
    )
    fringeSenders.iterator.map(bonds).sum * 3 > bonds.valuesIterator.sum * 2
  }

  def isProvisional[M, S](fringe: Fringe[M, S])(bonds: Map[S, Long]) = !isFinal(fringe)(bonds)

  /** Full fringe have to go across all bonded senders in the shard. It's more then final*/
  def isComplete[M, S](fringe: Fringe[M, S], bondedSenders: Set[S]) = fringe.keySet == bondedSenders

  /** Multiple fringes can be reconciled to become a single fringe. */
  trait Reconciler[F[_], M, S] {
    def reconcile(fringes: List[Fringe[M, S]]): F[Fringe[M, S]]
  }

  /** Combine two fringes. Note: this does not include reconciliation. */
  def combine[M, S](x: Fringe[M, S], y: Fringe[M, S])(seqNum: M => Long): Fringe[M, S] =
    x ++ y.filter { case (s, m) => seqNum(m) > seqNum(x(s)) }

  /**
    * Reconciler that does not do anything except merging shapes of input fringes.
    * Conflict resolution and merge is delayed, e.g. to the moment of real finalizations.
    */
  final case class LazyReconciler[F[_]: Applicative, M, S](seqNum: M => Long)
      extends Reconciler[F, M, S] {
    override def reconcile(fringes: List[Fringe[M, S]]): F[Fringe[M, S]] = {
      require(fringes.nonEmpty, "Reconciling empty list of fringes.")
      fringes.reduce(combine(_, _)(seqNum)).pure[F]
    }
  }

  /** Reconciler that does conflict resolution and merge is done immediately. */
  final case class EagerReconciler[F[_]: Monad, M, S](
      seqNum: M => Long,
      bonds: Map[S, Long],
      reconcilePair: (Fringe[M, S], Fringe[M, S]) => F[Unit]
  ) extends Reconciler[F, M, S] {
    override def reconcile(fringes: List[Fringe[M, S]]): F[Fringe[M, S]] = {
      require(fringes.nonEmpty, "Reconciling empty list of fringes.")
      val sorted = fringes.sortBy { _.keysIterator.map(bonds).sum }.reverse
      sorted.tail.foldLeftM(sorted.head) {
        case (acc, fringe) => reconcilePair(acc, fringe).as(combine(acc, fringe)(seqNum))
      }
    }
  }
}
