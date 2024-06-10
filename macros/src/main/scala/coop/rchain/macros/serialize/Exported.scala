package coop.rchain.macros.serialize

// Workaround for issues with magnolia trying to derive instances having custom instances in scope.
// Read more about that workaround here: https://github.com/propensive/magnolia/issues/107#issuecomment-589289260
final case class Exported[A](instance: A) extends AnyVal

import cats.Eval
import coop.rchain.sdk.serialize.Serialize
import magnolia1.Magnolia

import scala.reflect.macros.whitebox

object ExportedMagnolia {
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def exportedMagnolia[TC[_], A: c.WeakTypeTag](c: whitebox.Context): c.Expr[Exported[TC[A]]] = {
    val magnoliaTree = c.Expr[TC[A]](Magnolia.gen[A](c))
    c.universe.reify(Exported(magnoliaTree.splice))
  }
}

trait ExportedSerialize {
  implicit def exportedSerialize[A](
      implicit exported: Exported[Serialize[Eval, A]]
  ): Serialize[Eval, A] =
    exported.instance
}
