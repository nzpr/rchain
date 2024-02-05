package io.rhonix.rholang.normalizer.env

trait FreeVarScope[F[_]] {

  /** Run function in an empty free variables scope (preserving history) */
  def withNewFreeVarScope[R](scopeFn: F[R]): F[R]
}

object FreeVarScope {
  def apply[F[_]](implicit instance: FreeVarScope[F]): FreeVarScope[F] = instance
}
