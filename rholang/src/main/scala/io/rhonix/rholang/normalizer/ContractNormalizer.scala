package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.normalizer.syntax.all.*
import io.rhonix.rholang.types.{ReceiveBindN, ReceiveN}

import scala.jdk.CollectionConverters.*

object ContractNormalizer {
  def normalizeContract[
      F[_]: Sync: NormalizerRec: BoundVarScope: FreeVarScope: NestingInfoWriter,
      T: BoundVarWriter: FreeVarReader
  ](p: PContr): F[ReceiveN] =
    for {
      source <- NormalizerRec[F].normalize(p.name_)

      normalizePattern = for {
        patterns  <- p.listname_.asScala.toList.traverse(NormalizerRec[F].normalize)
        reminder  <- NormalizerRec[F].normalize(p.nameremainder_)
        freeCount = FreeVarReader[T].getFreeVars.size
      } yield ReceiveBindN(patterns, source, reminder, freeCount)
      patternTuple <- normalizePattern.withinPatternGetFreeVars(withinReceive = true)

      (bind, freeVars) = patternTuple

      // Normalize body in the copy of bound scope with added free variables as bounded
      continuation <- NormalizerRec[F].normalize(p.proc_).withAbsorbedFreeVars(freeVars)

    } yield ReceiveN(bind, continuation, persistent = true, peek = false, freeVars.size)
}
