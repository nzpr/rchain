package io.rhonix.rholang.normalizer

import cats.Applicative
import cats.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.types.{CollectionN, EListN, EMapN, ESetN, ETupleN}

import scala.jdk.CollectionConverters.*

object CollectNormalizer {
  def normalizeCollect[F[_]: Applicative: NormalizerRec](p: PCollect): F[CollectionN] =
    // NOTE: Order of processing remainder or processes doesn't matter because they are independent.
    p.collection_ match {
      case cl: CollectList =>
        (
          NormalizerRec[F].normalize(cl.procremainder_),
          cl.listproc_.asScala.toList.traverse(NormalizerRec[F].normalize)
        ).mapN((remainder, ps) => EListN(ps, remainder))

      case ct: CollectTuple =>
        val ps = ct.tuple_ match {
          case ts: TupleSingle   => List(ts.proc_)
          case tm: TupleMultiple => tm.proc_ +: tm.listproc_.asScala.toList
        }
        ps.traverse(NormalizerRec[F].normalize).map(ETupleN.apply)

      case cs: CollectSet =>
        (
          NormalizerRec[F].normalize(cs.procremainder_),
          cs.listproc_.asScala.toList.traverse(NormalizerRec[F].normalize)
        ).mapN((remainder, ps) => ESetN(ps, remainder))

      case cm: CollectMap =>
        (
          NormalizerRec[F].normalize(cm.procremainder_),
          cm.listkeyvaluepair_.asScala.toList.traverse {
            case kv: KeyValuePairImpl =>
              (NormalizerRec[F].normalize(kv.proc_1), NormalizerRec[F].normalize(kv.proc_2))
                .mapN((_, _))
          }
        ).mapN((remainder, ps) => EMapN(ps, remainder))
    }
}
