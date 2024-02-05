package io.rhonix.rholang.normalizer.envimpl

import coop.rchain.rholang.interpreter.compiler.FreeContext
import io.rhonix.rholang.normalizer.env.*

final case class FreeVarReaderImpl[T](
    private val getFn: String => Option[VarContext[T]],
    private val getAllFn: () => Seq[(String, VarContext[T])]
) extends FreeVarReader[T] {

  override def getFreeVars: Seq[(String, FreeContext[T])] =
    getAllFn().map {
      case (name, VarContext(index, typ, sourcePosition)) =>
        (name, FreeContext(index, typ, sourcePosition))
    }

  override def getFreeVar(name: String): Option[FreeContext[T]] = getFn(name).map {
    case VarContext(index, typ, sourcePosition) => FreeContext(index, typ, sourcePosition)
  }
}
