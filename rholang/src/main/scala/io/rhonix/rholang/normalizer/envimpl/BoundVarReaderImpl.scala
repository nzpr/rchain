package io.rhonix.rholang.normalizer.envimpl

import coop.rchain.rholang.interpreter.compiler.BoundContext
import io.rhonix.rholang.normalizer.env.*

final case class BoundVarReaderImpl[T](
    private val getFn: String => Option[VarContext[T]],
    private val findFn: String => Option[(VarContext[T], Int)]
) extends BoundVarReader[T] {

  override def getBoundVar(name: String): Option[BoundContext[T]] = getFn(name).map {
    case VarContext(index, typ, sourcePosition) => BoundContext(index, typ, sourcePosition)
  }

  override def findBoundVar(name: String): Option[(BoundContext[T], Int)] = findFn(name).map {
    case (VarContext(index, typ, sourcePosition), depth) =>
      (BoundContext(index, typ, sourcePosition), depth)
  }
}
