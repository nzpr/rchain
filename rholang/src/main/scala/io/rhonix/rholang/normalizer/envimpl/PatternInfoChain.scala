package io.rhonix.rholang.normalizer.envimpl

import cats.effect.Sync
import io.rhonix.rholang.normalizer.syntax.all.*

/**
  * Represents a chain of pattern information.
  * @param chain a history chain of tuples, where each tuple represents a status of a pattern.
  */
final class PatternInfoChain[F[_]: Sync](
    private val chain: HistoryChain[(Boolean, Boolean)]
) {

  /**
    * Runs a scope function with a new status.
    *
    * @param inReceive a boolean flag indicating whether the scope function is in receive term.
    * @param scopeFn the scope function to run.
    * @tparam R the type of the result of the scope function.
    * @return the result of the scope function, wrapped in the effect type F.
    */
  def runWithNewStatus[R](inReceive: Boolean)(scopeFn: F[R]): F[R] =
    chain.runWithNewDataInChain(scopeFn, (true, inReceive))

  /**
    * Gets the current status of the pattern information chain.
    *
    * @return a tuple representing the current status of the pattern information chain.
    *         The first Boolean indicates whether we are within a pattern.
    *         The second Boolean indicates whether we are within a top level receive.
    */
  def getStatus: (Boolean, Boolean) = chain.current()
}

object PatternInfoChain {
  def apply[F[_]: Sync](): PatternInfoChain[F] =
    new PatternInfoChain(HistoryChain(Seq((false, false))))
}
