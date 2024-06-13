package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.SourcePosition
import coop.rchain.rholang.interpreter.errors.{
  PatternReceiveError,
  TopLevelLogicalConnectivesNotAllowedError
}
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.NestingInfoReader
import io.rhonix.rholang.types.ConnOrN
import sdk.syntax.all.*

object DisjunctionNormalizer {
  def normalizeDisjunction[F[_]: Sync: NormalizerRec](
      p: PDisjunction
  )(implicit nestingInfo: NestingInfoReader): F[ConnOrN] = {
    def pos = SourcePosition(p.line_num, p.col_num)

    if (nestingInfo.insidePattern)
      if (!nestingInfo.insideTopLevelReceivePattern)
        (p.proc_1, p.proc_2)
          .nmap(NormalizerRec[F].normalize)
          .mapN((left, right) => ConnOrN(Seq(left, right)))
      else
        // TODO: According to Rholang documentation:
        //  https://github.com/rchain/rchain/blob/25e523580a339db9ce2e8abdc9dcab44618d4c5c/docs/rholang/rholangtut.md?plain=1#L244-L252
        //  Since we cannot rely on a specific pattern matching order,
        //  we cannot use patterns separated by \/ to bind any variables in top level receive.
        //  But, if part of the connectives does not contain free variables, disjunction and negation can be used.
        //  For example, this code: for(@{ @"grade"!(10) \/ @"grade"!(11) } <- ... ){ ... } is acceptable.
        //  Therefore, this condition contradicts the documentation, and it is preserved for compatibility with the legacy normalizer.
        //  In the future, it will be necessary to analyze whether the left and right parts of the connective contain free variables
        //  and only in such cases return a PatternReceiveError.
        PatternReceiveError(s"\\/ (disjunction) at $pos").raiseError
    else
      TopLevelLogicalConnectivesNotAllowedError(s"\\/ (disjunction) at $pos").raiseError
  }
}
