package coop.rchain.blockstorage.dag

import cats.Show
import cats.kernel.Monoid
import cats.syntax.all._
import coop.rchain.sdk.dag.View
import coop.rchain.sdk.dag.View.IncludePolicy
import coop.rchain.sdk.syntax.all.mapSyntax

trait MessageMapSyntax {
  implicit def blockStorageSyntaxMessageMap[M, S](
      msgMap: Map[M, Message[M, S]]
  ): MessageMapSyntaxOps[M, S] =
    new MessageMapSyntaxOps[M, S](msgMap)
}

final class MessageMapSyntaxOps[M, S](private val msgMap: Map[M, Message[M, S]]) extends AnyVal {

  type Msg = Message[M, S]

  /**
    * Gets the slice of messages between upper and lower bound (including upper bound messages)
    */
  def between(
      upperBound: Set[M],
      lowerBound: Set[M],
      lookup: (S, Long) => M,
      includePolicy: IncludePolicy
  )(implicit sM: Show[M], sS: Show[S]): Set[M] = {
    println(s"between ${upperBound.map(_.show)} and ${lowerBound.map(_.show)}")
    val upperAsSeen = View[S](
      upperBound
        .map(msgMap.getUnsafe)
        .map(x => x.sender -> Range.inclusive(x.senderSeq.toInt, x.senderSeq.toInt))
        .toMap
    )
    val bottomAsSeen = View[S](
      lowerBound
        .map(msgMap.getUnsafe)
        .map(x => x.sender -> Range.inclusive(x.senderSeq.toInt, x.senderSeq.toInt))
        .toMap
    )
    View
      .diff(
        Monoid[View[S]].combineAll(upperBound.map(msgMap.getUnsafe).map(_.seen) + upperAsSeen),
        Monoid[View[S]].combineAll(lowerBound.map(msgMap.getUnsafe).map(_.seen) + bottomAsSeen),
        includePolicy
      )
      .seen
      .iterator
      .flatMap {
        case (v, r) =>
          println(s"${v.show} -> $r")
          r.map(_.toLong).map(v -> _)
      }
      .map(lookup.tupled)
      .toSet
  }

  /**
    * Latest fringe seen from justifications
    * - can be empty which means first layer is the first message from each sender
    */
  // TODO: should fringes read bonds map from each round if multiple are finalized???
  def latestFringe(justifications: Set[Message[M, S]]): Set[Message[M, S]] =
    justifications.toList
      .maximumByOption(_.fringe.map(msgMap).toList.map(_.height).maximumOption.getOrElse(-1L))
      .map(_.fringe)
      .getOrElse(Set())
      .map(msgMap)

  /**
    * Lowest fringe for input messages
    */
  def lowestFringe(msgs: Set[Message[M, S]])(implicit showM: Show[M]): Set[Message[M, S]] = {
    val x = msgs
      .map(_.fringe)
      .toList
      .minimumByOption { x =>
        // empty fringe is always the lowest
        if (x.isEmpty) Long.MinValue
        else {
          val fringeBottomHeight = x.map(msgMap.getUnsafeShow).toList.map(_.height)
          fringeBottomHeight.min
        }
      }
      .getOrElse(Set())

    println(s"lowestFringe ${msgs.map(_.id.show.take(4))} => ${x.map(_.show.take(4))}")
    x.map { msgMap.getUnsafeShow }
  }

  /**
    * Finds a message with empty parents
    */
  def findWithEmptyParents: Option[Msg] = msgMap.values.find(_.parents.isEmpty)

  /**
    * The highest fringe that is not required for merging.
    */
  def pruneFringe(
      finalFringe: Set[M],
      childMap: Map[M, Set[M]]
  )(implicit show: Show[M]): Set[Message[M, S]] =
    lowestFringe(finalFringe.flatMap(childMap.getUnsafeShow).map(msgMap.getUnsafeShow))
}
