package coop.rchain.casper.serialize

import com.google.protobuf.ByteString
import coop.rchain.casper.merging.{DeployChainIndex, DeployIdWithCost}
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.trace.{Consume, Produce}
import cats.syntax.all._
import coop.rchain.models.syntax.modelsSyntaxByteString

import scala.math.Ordered.orderingToOrdered

trait OrderingRules {
  // Orderings required for auto derivation of serialize for sum types
  implicit val bhOrd: Ordering[Blake2b256Hash] = Blake2b256Hash.ordering
  implicit val pOrd: Ordering[Produce]         = Ordering.by[Produce, Blake2b256Hash](_.hash)
  implicit val cOrd: Ordering[Consume]         = Ordering.by[Consume, Blake2b256Hash](_.hash)
  implicit val bhsOrd: Ordering[Seq[Blake2b256Hash]] = new Ordering[Seq[Blake2b256Hash]] {
    override def compare(x: Seq[Blake2b256Hash], y: Seq[Blake2b256Hash]): Int =
      if (x.size > y.size) 1
      else if (x.size == y.size) {
        (x zip y).find { case (l, r) => l > r }.as(-1).getOrElse(1)
      } else -1
  }
  implicit val diwcOrd: Ordering[DeployIdWithCost] = {
    import coop.rchain.models.syntax.ordering
    Ordering.by[DeployIdWithCost, ByteString](_.id)
  }
  implicit val dciOrd: Ordering[DeployChainIndex] = {
    import coop.rchain.models.syntax.ordering
    Ordering.by[DeployChainIndex, ByteString] { x =>
      x.deploysWithCost.map(_.id).toList.sortBy(_.toBlake2b256Hash).reduce(_ concat _)
    }
  }
}
