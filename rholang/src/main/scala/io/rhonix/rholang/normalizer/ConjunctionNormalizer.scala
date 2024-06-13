package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.SourcePosition
import coop.rchain.rholang.interpreter.errors.TopLevelLogicalConnectivesNotAllowedError
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.NestingInfoReader
import io.rhonix.rholang.types.ConnAndN
import sdk.syntax.all.*

object ConjunctionNormalizer {
  def normalizeConjunction[F[_]: Sync: NormalizerRec](
      p: PConjunction
  )(implicit nestingInfo: NestingInfoReader): F[ConnAndN] =
    if (nestingInfo.insidePattern)
      (p.proc_1, p.proc_2)
        .nmap(NormalizerRec[F].normalize)
        .mapN((left, right) => ConnAndN(Seq(left, right)))
    else {
      def pos = SourcePosition(p.line_num, p.col_num)
      TopLevelLogicalConnectivesNotAllowedError(s"/\\ (conjunction) at $pos").raiseError
    }
}
