package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.rholang.interpreter.compiler.{NameSort, SourcePosition, VarSort}
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.types.{
  BoundVarN,
  NewN,
  NilN,
  ParN,
  ReceiveBindN,
  ReceiveN,
  SendN,
  WildcardN
}
import sdk.syntax.all.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

object SendSynchNormalizer {
  def normalizeSendSynch[F[_]: Sync: NormalizerRec](p: PSendSynch): F[ParN] = Sync[F].defer {
    val identifier = UUID.randomUUID().toString
    val nameVar    = new NameVar(identifier)

    val send: PSend = {
      p.listproc_.asScala.prepend(new PEval(nameVar)).void()
      new PSend(p.name_, new SendSingle(), p.listproc_)
    }

    val receive: PInput = {
      val listName = new ListName()
      listName.add(new NameWildcard).void()

      val listLinearBind = new ListLinearBind()
      listLinearBind
        .add(new LinearBindImpl(listName, new NameRemainderEmpty, new SimpleSource(nameVar)))
        .void()

      val listReceipt = new ListReceipt()
      listReceipt.add(new ReceiptLinear(new LinearSimple(listLinearBind))).void()

      new PInput(
        listReceipt,
        p.synchsendcont_ match {
          case _: EmptyCont               => new PNil()
          case nonEmptyCont: NonEmptyCont => nonEmptyCont.proc_
        }
      )
    }

    val listName = new ListNameDecl()
    listName.add(new NameDeclSimpl(identifier)).void()
    NormalizerRec[F].normalize(new PNew(listName, new PPar(send, receive)))
  }

  /** Normalizes synchronous send by transformation to send/receive pair with generated sync channel.
    *
    * NOTE: This is a new version of sync send AST transformation. It uses AST types and not parser types which enables
    *       avoiding using random string as a name of generated variable to ensure uniqueness.
    *       By using bound variable scope directly we can generate new bounded variable without syntax level name.
    *
    * TODO: To avoid using random var name, create variant of `putBoundVars` to add bound var without String name.
    *
    * {{{
    *   // Input: Two sync send expressions.
    *   @1!?(2) ; @3!?(4).
    *
    *   // Output: Generated AST with two send/receive pairs.
    *   new varGen1 {
    *     @1!(varGen1, 2) |
    *     for(_ <- varGen1) {
    *
    *       new varGen2 {
    *         @3!(varGen2, 4) |
    *         for(_ <- varGen2) {
    *           Nil
    *         }
    *       }
    *
    *     }
    *   }
    * }}}
    *
    * @param p sync send parser AST object
    * @return transformed [[ParN]] AST object
    */
  def normalizeSendSynchNew[F[_]: Sync: NormalizerRec: BoundVarScope, T >: VarSort: BoundVarWriter](
      p: PSendSynch
  ): F[ParN] =
    BoundVarScope[F].withCopyBoundVarScope {
      for {
        identifier <- Sync[F].delay(UUID.randomUUID().toString)
        // Source position of generated channel is the whole input expression
        varPos        = SourcePosition(p.line_num, p.col_num)
        Seq(varIndex) = BoundVarWriter[T].putBoundVars(Seq((identifier, NameSort, varPos)))
        // TODO: To avoid using random var name, create variant of `putBoundVars` to add bound vars without String name.
        // Seq(varIndex) = BoundVarWriter[T].createBoundVars(count = 1)
        varGen = BoundVarN(varIndex)

        // Send on the same channel, but prepend generated name to send data
        chan <- NormalizerRec[F].normalize(p.name_)
        data <- p.listproc_.asScala.toVector.traverse(NormalizerRec[F].normalize)
        send = SendN(chan, varGen +: data)

        // Receive body is Nil when sync send ends with `.` or normalizes proc after `;`.
        body <- p.synchsendcont_ match {
                 case _: EmptyCont               => Sync[F].pure(NilN)
                 case nonEmptyCont: NonEmptyCont => NormalizerRec[F].normalize(nonEmptyCont.proc_)
               }
        bind    = ReceiveBindN(WildcardN, varGen)
        receive = ReceiveN(bind, body, 1)

        // Return send/receive pair wrapped with a new binding
      } yield NewN(
        bindCount = 1,
        p = ParN.combine(send, receive),
        uri = Seq(),
        injections = Map[String, ParN]()
      )
    }

}
