package io.rhonix.rholang.normalizer.envimpl

import cats.effect.Sync
import cats.implicits.toFoldableOps
import coop.rchain.rholang.interpreter.compiler.SourcePosition
import io.rhonix.rholang.normalizer.env.VarContext
import io.rhonix.rholang.normalizer.syntax.all.*

/**
  * Represents a chain of variable maps.
  *
  * @param chain a history chain of variable maps.
  * @tparam F the type of the effect.
  * @tparam T the type of the variable sort.
  */
final class VarMapChain[F[_]: Sync, T](private val chain: HistoryChain[VarMap[T]]) {

  /**
    * Runs a scope function with a new, empty variable map.
    *
    * @param scopeFn the scope function to run.
    * @tparam R the type of the result of the scope function.
    * @return the result of the scope function, wrapped in the effect type F.
    */
  def withNewScope[R](scopeFn: F[R]): F[R] = chain.runWithNewDataInChain(scopeFn, VarMap.empty[T])

  /**
    * Runs a scope function with a copy of the current variable map.
    *
    * @param scopeFn the scope function to run.
    * @tparam R the type of the result of the scope function.
    * @return the result of the scope function, wrapped in the effect type F.
    */
  def withCopyScope[R](scopeFn: F[R]): F[R] = chain.runWithNewDataInChain(scopeFn, chain.current())

  /**
    * Adds a new variable to the current variable map and returns its index.
    *
    * @param name the name of the variable.
    * @param sort the sort of the variable.
    * @param sourcePosition the source position of the variable.
    * @return the index of the added variable.
    */
  def putVar(name: String, sort: T, sourcePosition: SourcePosition): Int = {
    chain.updateCurrent(_.put(name, sort, sourcePosition))
    chain.current().get(name).get.index
  }

  /**
    * Retrieves a variable from the current variable map.
    *
    * @param name the name of the variable.
    * @return an option containing the variable context if the variable exists, None otherwise.
    */
  def getVar(name: String): Option[VarContext[T]] = chain.current().get(name)

  /**
    * Retrieves all variables in the current scope.
    *
    * @return a sequence of tuples, where each tuple contains the name of a variable and its context.
    */
  def getAllInScope: Seq[(String, VarContext[T])] = chain.current().getAll

  /**
    * Searches for a variable in the chain of variable maps and returns the first match along with its depth.
    *
    * @param name the name of the variable.
    * @return an option containing a tuple with the variable context and its depth if the variable exists, None otherwise.
    */
  def getFirstVarInChain(name: String): Option[(VarContext[T], Int)] =
    chain.iter.zipWithIndex.toSeq.collectFirstSome {
      case (boundMap, depth) => boundMap.get(name).map((_, depth))
    }

  /**
    * Returns an iterator over the variable maps in the chain.
    */
  def iter: Iterator[VarMap[T]] = chain.iter
}

object VarMapChain {

  /**
    * Creates a new variable map chain with one initial variable map.
    *
    * @param initVarMap the variable map to use.
    */
  def apply[F[_]: Sync, T](initVarMap: VarMap[T]): VarMapChain[F, T] = apply(Seq(initVarMap))

  /**
    * Creates a new variable map chain with the given variable maps.
    *
    * @param initVarMaps the variable maps to use.
    */
  def apply[F[_]: Sync, T](initVarMaps: Seq[VarMap[T]]): VarMapChain[F, T] =
    new VarMapChain(HistoryChain(initVarMaps))

  /**
    * Creates a new variable map chain with an empty variable map.
    */
  def empty[F[_]: Sync, T]: VarMapChain[F, T] = apply(VarMap.empty[T])

}
