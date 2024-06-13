package io.rhonix.rholang.normalizer

import cats.Apply
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.{PMatches, Proc}
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.normalizer.syntax.all.*
import io.rhonix.rholang.types.EMatchesN

object MatchesNormalizer {
  def normalizeMatches[F[_]: Apply: NormalizerRec: BoundVarScope: FreeVarScope: NestingInfoWriter](
      p: PMatches
  ): F[EMatchesN] = {
    // The expression "target matches pattern" should have the same semantics as "match target { pattern => true ; _ => false}".
    // Therefore, there is no need to bind free variables in the pattern because the case body will always be true.
    def normalizePattern(proc: Proc) = NormalizerRec[F].normalize(proc).withinPattern()

    (NormalizerRec[F].normalize(p.proc_1), normalizePattern(p.proc_2)).mapN(EMatchesN.apply)
  }
}
