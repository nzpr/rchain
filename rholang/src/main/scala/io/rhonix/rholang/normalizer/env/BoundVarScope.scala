package io.rhonix.rholang.normalizer.env

trait BoundVarScope[F[_]] {

  /** Runs functions in an empty bound variables scope (preserving history) */
  def withNewBoundVarScope[R](scopeFn: F[R]): F[R]

  /** Runs functions in an copy of this bound variables scope (preserving history) */
  def withCopyBoundVarScope[R](scopeFn: F[R]): F[R]
}

object BoundVarScope {
  def apply[F[_]](implicit instance: BoundVarScope[F]): BoundVarScope[F] = instance
}
