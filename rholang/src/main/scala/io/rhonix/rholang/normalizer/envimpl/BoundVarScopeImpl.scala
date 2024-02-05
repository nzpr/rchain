package io.rhonix.rholang.normalizer.envimpl

import io.rhonix.rholang.normalizer.env.BoundVarScope

final case class BoundVarScopeImpl[F[_], T](private val chain: VarMapChain[F, T])
    extends BoundVarScope[F] {
  override def withNewBoundVarScope[R](scopeFn: F[R]): F[R]  = chain.withNewScope(scopeFn)
  override def withCopyBoundVarScope[R](scopeFn: F[R]): F[R] = chain.withCopyScope(scopeFn)
}
