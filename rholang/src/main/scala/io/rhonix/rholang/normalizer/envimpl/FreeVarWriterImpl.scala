package io.rhonix.rholang.normalizer.envimpl

import coop.rchain.rholang.interpreter.compiler.{IdContext, SourcePosition}
import io.rhonix.rholang.normalizer.env.FreeVarWriter

final case class FreeVarWriterImpl[T](private val putFn: (String, T, SourcePosition) => Int)
    extends FreeVarWriter[T] {

  override def putFreeVar(binding: IdContext[T]): Int = putFn.tupled(binding)
}
