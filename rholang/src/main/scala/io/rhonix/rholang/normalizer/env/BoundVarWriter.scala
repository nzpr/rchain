package io.rhonix.rholang.normalizer.env

import coop.rchain.rholang.interpreter.compiler.IdContext

trait BoundVarWriter[T] {

  /**
    * Inserts all bindings into the bound map and returns a sequence of indices.
    * The returned indices are those that haven't been shadowed by the new bindings.
    *
    * @param bindings a sequence of tuples, where each tuple contains a variable name and its context.
    * @return a sequence of indices of the inserted bindings that haven't been shadowed.
    */
  def putBoundVars(bindings: Seq[IdContext[T]]): Seq[Int]
}

object BoundVarWriter {
  def apply[T](implicit instance: BoundVarWriter[T]): BoundVarWriter[T] = instance
}
