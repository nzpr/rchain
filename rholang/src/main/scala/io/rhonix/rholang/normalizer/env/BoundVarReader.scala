package io.rhonix.rholang.normalizer.env

import coop.rchain.rholang.interpreter.compiler.BoundContext

trait BoundVarReader[T] {

  /** Gets bound variable by name, current level */
  def getBoundVar(name: String): Option[BoundContext[T]]

  /** Finds bound variable, searching parent levels */
  def findBoundVar(name: String): Option[(BoundContext[T], Int)]
}

object BoundVarReader {
  def apply[T](implicit instance: BoundVarReader[T]): BoundVarReader[T] = instance
}
