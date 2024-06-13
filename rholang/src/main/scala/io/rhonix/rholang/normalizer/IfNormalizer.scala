package io.rhonix.rholang.normalizer

import cats.Apply
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.{PIf, PIfElse}
import io.rhonix.rholang.types.{GBoolN, MatchCaseN, MatchN, NilN}
import sdk.syntax.all.*

object IfNormalizer {
  def normalizeIf[F[_]: Apply: NormalizerRec](p: PIf): F[MatchN] =
    (p.proc_1, p.proc_2)
      .nmap(NormalizerRec[F].normalize)
      .mapN(
        (target, trueCaseBody) =>
          MatchN(
            target,
            Seq(MatchCaseN(GBoolN(true), trueCaseBody), MatchCaseN(GBoolN(false), NilN))
          )
      )

  def normalizeIfElse[F[_]: Apply: NormalizerRec](p: PIfElse): F[MatchN] =
    (p.proc_1, p.proc_2, p.proc_3)
      .nmap(NormalizerRec[F].normalize)
      .mapN(
        (target, trueCaseBody, falseCaseBody) =>
          MatchN(
            target,
            Seq(MatchCaseN(GBoolN(true), trueCaseBody), MatchCaseN(GBoolN(false), falseCaseBody))
          )
      )
}
