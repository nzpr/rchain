package io.rhonix.rholang.normalizer.syntax

import cats.Functor
import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.{FreeContext, IdContext}
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.normalizer.syntax.all.*

trait NormalizerSyntax {
  implicit def rholangNormalizerSyntax[F[_], A](f: F[A]): NormalizerOps[F, A] =
    new NormalizerOps[F, A](f)
}

class NormalizerOps[F[_], A](val f: F[A]) extends AnyVal {

  /** Run a function within a new scope, label it as a pattern
    * @param withinReceive Flag should be true for pattern in receive (input) or contract. */
  def withinPattern(
      withinReceive: Boolean = false
  )(implicit bvs: BoundVarScope[F], fvs: FreeVarScope[F], nes: NestingInfoWriter[F]): F[A] =
    bvs.withNewBoundVarScope(fvs.withNewFreeVarScope(nes.withinPattern(withinReceive)(f)))

  /** Run a function within a new scope, label it as a pattern,
    * and subsequently extract all free variables from the normalized result of this function.
    * @param withinReceive Flag should be true for pattern in receive (input) or contract. */
  def withinPatternGetFreeVars[T](withinReceive: Boolean = false)(
      implicit
      fun: Functor[F],
      bvs: BoundVarScope[F],
      fvs: FreeVarScope[F],
      nes: NestingInfoWriter[F],
      fvr: FreeVarReader[T]
  ): F[(A, Seq[(String, FreeContext[T])])] =
    f.withinPattern(withinReceive).map((_, FreeVarReader[T].getFreeVars))

  /** Run function with restricted conditions with restrictions as for the bundle */
  def withinBundle()(implicit nes: NestingInfoWriter[F]): F[A] =
    NestingInfoWriter[F].withinBundle(f)

  /** Bound free variables in a copy of the current scope.
    *
    * Free variables are sorted by levels and then added with indexes:
    * {i0, i1, ..., iN} = {fl0 + last + 1, fl1 + last + 1, ..., flN + last + 1}.
    * Here, i0, ..., iN represent the de Bruijn indices of the new bound vars,
    * fl0, ..., flN are the de Bruijn levels of the inserted free vars,
    * last is the last index among all bound vars at the moment.
    */
  def withAbsorbedFreeVars[T](
      freeVars: Seq[(String, FreeContext[T])]
  )(implicit sync: Sync[F], bvs: BoundVarScope[F], bvw: BoundVarWriter[T]): F[A] = {

    def absorbFree(freeVars: Seq[(String, FreeContext[T])]): Seq[IdContext[T]] = {
      val sortedByLevel = freeVars.sortBy(_._2.level)
      val (levels, data) =
        sortedByLevel.unzip(fv => (fv._2.level, (fv._1, fv._2.typ, fv._2.sourcePosition)))
      assert(
        levels == levels.indices,
        "Error when absorbing free variables during normalization: incorrect de Bruijn levels." +
          s"Should be ${levels.indices}, but was $levels."
      )
      data
    }
    f.withAddedBoundVars(absorbFree(freeVars)).map(_._1)
  }

  /** Put new bound variables in a copy of the current scope.
    * @return result of the effect and the number of inserted non-duplicate variables
    */
  def withAddedBoundVars[T](
      boundVars: Seq[IdContext[T]]
  )(implicit sync: Sync[F], bvs: BoundVarScope[F], bvw: BoundVarWriter[T]): F[(A, Seq[Int])] =
    BoundVarScope[F].withCopyBoundVarScope(for {
      indices <- Sync[F].delay(BoundVarWriter[T].putBoundVars(boundVars))
      fRes    <- f
    } yield (fRes, indices))
}
