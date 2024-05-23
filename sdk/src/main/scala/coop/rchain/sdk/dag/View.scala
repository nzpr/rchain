package coop.rchain.sdk.dag

import cats.Monoid

// TODO Range here uses Int, but Long is used now to represent sequence numbers.
//  Create custom RangeLong or implement another way to safely use Range here
final case class View[S](seen: Map[S, Range]) {
  assert(seen.forall(_._2.step == 1)) // ranges has to be continuous
}

object View {
  trait IncludePolicy
  object IncludeTop    extends IncludePolicy
  object IncludeBottom extends IncludePolicy
  object IncludeNone   extends IncludePolicy

  implicit def semigroupDagSeen[S]: Monoid[View[S]] = new Monoid[View[S]] {
    def combine(x: View[S], y: View[S]): View[S] = {
      // new seen for each sender is from lowest amongst the two to highest amongst the two
      val newSeen = x.seen.foldLeft(y.seen) {
        case (acc, (sender, range)) =>
          acc.get(sender) match {
            case None => acc + (sender -> range)
            case Some(range2) =>
              val newRange = (range.start min range2.start) to (range.end max range2.end)
              acc + (sender -> newRange)
          }
      }
      View(newSeen)
    }
    override def empty: View[S] = View(Map.empty)
  }

  def diff[S](postfix: View[S], prefix: View[S], includePolicy: IncludePolicy): View[S] = {
    val postfixOnly = postfix.seen -- prefix.seen.keys
    val newSeen = postfix.seen.foldLeft(prefix.seen ++ postfixOnly) {
      case (acc, (sender, range2)) =>
        acc.get(sender) match {
          case None =>
            val newRange = includePolicy match {
              case IncludeTop    => range2.start to range2.end
              case IncludeNone   => range2.start until range2.end // exclusive range
              case IncludeBottom => range2.start until range2.end // exclusive range
            }
            acc + (sender -> newRange)
          case Some(range1) =>
            val newRange = includePolicy match {
              case IncludeTop    => (range1.end + 1) to (range2.end)
              case IncludeNone   => (range1.end + 1) until (range2.end)
              case IncludeBottom => range1.end until (range2.end)
            }
            acc + (sender -> newRange)
        }
    }
    View(newSeen)
  }

  def compute[S, M](
      justifications: Set[M],
      sender: M => S,
      seqNum: M => Long,
      seen: M => View[S]
  ): View[S] = {
    val parentsAsSeen = View(
      justifications
        .map(x => sender(x) -> Range.inclusive(seqNum(x).toInt, seqNum(x).toInt))
        .toMap
    )
    Monoid[View[S]].combineAll(justifications.map(seen) + parentsAsSeen)
  }
}
