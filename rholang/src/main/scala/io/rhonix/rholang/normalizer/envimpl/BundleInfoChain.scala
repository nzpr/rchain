package io.rhonix.rholang.normalizer.envimpl

import cats.effect.Sync
import io.rhonix.rholang.normalizer.syntax.all.*

/**
  * Represents a chain of bundle information.
  *
  * @param chain a history chain of booleans, where each boolean represents a status of a bundle.
  */
final class BundleInfoChain[F[_]: Sync](private val chain: HistoryChain[Boolean]) {

  /**
    * Runs a scope function with a new status.
    *
    * @param scopeFn the scope function to run.
    * @return the result of the scope function, wrapped in the effect type F.
    */
  def runWithNewStatus[R](scopeFn: F[R]): F[R] = chain.runWithNewDataInChain(scopeFn, true)

  /**
    * Gets the current status of the bundle information chain.
    *
    * @return a boolean representing the current status of the bundle information chain.
    */
  def getStatus: Boolean = chain.current()
}

object BundleInfoChain {
  def apply[F[_]: Sync](): BundleInfoChain[F] = new BundleInfoChain(HistoryChain(Seq(false)))
}
