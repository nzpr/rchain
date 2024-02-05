package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.normalizer.GroundNormalizeMatcher.{
  stripString,
  stripUri
}
import coop.rchain.rholang.interpreter.errors.NormalizerError
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.types.{ExprN, GBigIntN, GBoolN, GIntN, GStringN, GUriN}

object GroundNormalizer {
  def normalizeGround[F[_]: Sync](p: PGround): F[ExprN] = Sync[F].defer {
    p.ground_ match {
      case gb: GroundBool =>
        gb.boolliteral_ match {
          case _: BoolFalse => Sync[F].pure(GBoolN(false))
          case _: BoolTrue  => Sync[F].pure(GBoolN(true))
        }
      case gi: GroundInt =>
        Sync[F]
          .delay(gi.longliteral_.toLong)
          .adaptError { case e: NumberFormatException => NormalizerError(e.getMessage) }
          .map(GIntN.apply)
      case gbi: GroundBigInt =>
        Sync[F]
          .delay(BigInt(gbi.longliteral_))
          .adaptError { case e: NumberFormatException => NormalizerError(e.getMessage) }
          .map(GBigIntN.apply)
      case gs: GroundString => Sync[F].delay(GStringN(stripString(gs.stringliteral_)))
      case gu: GroundUri    => Sync[F].delay(GUriN(stripUri(gu.uriliteral_)))
    }
  }
}
