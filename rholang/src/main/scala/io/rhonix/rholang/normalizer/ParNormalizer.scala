package io.rhonix.rholang.normalizer

import cats.Apply
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.PPar
import io.rhonix.rholang.types.ParN
import sdk.syntax.all.*

object ParNormalizer {
  def normalizePar[F[_]: Apply: NormalizerRec](p: PPar): F[ParN] =
    (p.proc_1, p.proc_2).nmap(NormalizerRec[F].normalize).mapN(ParN.combine)
}
