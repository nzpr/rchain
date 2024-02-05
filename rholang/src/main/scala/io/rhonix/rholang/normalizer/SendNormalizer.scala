package io.rhonix.rholang.normalizer

import cats.Applicative
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.types.SendN

import scala.jdk.CollectionConverters.*

object SendNormalizer {
  def normalizeSend[F[_]: Applicative: NormalizerRec](p: PSend): F[SendN] =
    (
      NormalizerRec[F].normalize(p.name_),
      p.listproc_.asScala.toVector.traverse(NormalizerRec[F].normalize)
    ).mapN { (chan, args) =>
      val persistent = p.send_ match {
        case _: SendSingle   => false
        case _: SendMultiple => true
      }
      SendN(chan, args, persistent)
    }
}
