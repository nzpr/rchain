package io.rhonix.rholang.normalizer.env

import coop.rchain.rholang.interpreter.compiler.IdContext

trait FreeVarWriter[T] {

  /** Puts free variables to the context
    * @return de Bruijn index of the added variable */
  def putFreeVar(binding: IdContext[T]): Int
}

object FreeVarWriter {
  def apply[T](implicit instance: FreeVarWriter[T]): FreeVarWriter[T] = instance
}
