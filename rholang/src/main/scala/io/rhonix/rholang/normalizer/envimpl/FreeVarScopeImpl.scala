package io.rhonix.rholang.normalizer.envimpl

import io.rhonix.rholang.normalizer.env.FreeVarScope

final case class FreeVarScopeImpl[F[_], T](private val chain: VarMapChain[F, T])
    extends FreeVarScope[F] {
  override def withNewFreeVarScope[R](scopeFn: F[R]): F[R] = chain.withNewScope(scopeFn)
}
