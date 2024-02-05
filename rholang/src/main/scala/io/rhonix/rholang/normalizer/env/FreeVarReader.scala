package io.rhonix.rholang.normalizer.env

import coop.rchain.rholang.interpreter.compiler.FreeContext

trait FreeVarReader[T] {

  /** Gets all free variables */
  def getFreeVars: Seq[(String, FreeContext[T])]

  /** Gets free variable */
  def getFreeVar(name: String): Option[FreeContext[T]]
}

object FreeVarReader {
  def apply[T](implicit instance: FreeVarReader[T]): FreeVarReader[T] = instance
}
