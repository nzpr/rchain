package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.SourcePosition
import coop.rchain.rholang.interpreter.errors.{InterpreterError, UnexpectedBundleContent}
import io.rhonix.rholang.normalizer.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.NestingInfoWriter
import io.rhonix.rholang.types.{BundleN, ConnectiveN, ParN, ParProcN}

object BundleNormalizer {
  def normalizeBundle[F[_]: Sync: NormalizerRec: NestingInfoWriter](p: PBundle): F[BundleN] = {
    def connectivesExistOnTop(p: ParN): Boolean =
      p match {
        case _: ConnectiveN  => true
        case pProc: ParProcN => pProc.ps.exists(connectivesExistOnTop)
        case _               => false
      }

    def returnError: F[InterpreterError] =
      UnexpectedBundleContent(
        s"Illegal top level connective in bundle at: ${SourcePosition(p.line_num, p.col_num)}."
      ).raiseError

    for {
      // Inside bundle target is prohibited to have free variables and wildcards.
      target <- NormalizerRec[F].normalize(p.proc_).withinBundle()
      // Inside bundle target is prohibited to have connectives on top level.
      _ <- returnError.whenA(connectivesExistOnTop(target))

      outermostBundle = p.bundle_ match {
        case _: BundleReadWrite => BundleN(target, writeFlag = true, readFlag = true)
        case _: BundleRead      => BundleN(target, writeFlag = false, readFlag = true)
        case _: BundleWrite     => BundleN(target, writeFlag = true, readFlag = false)
        case _: BundleEquiv     => BundleN(target, writeFlag = false, readFlag = false)
      }

    } yield target match {
      case b: BundleN => outermostBundle.merge(b)
      case _          => outermostBundle
    }
  }
}
