package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import io.rhonix.rholang.normalizer.syntax.all.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.types.{EListN, MatchCaseN, MatchN, ParN}
import sdk.syntax.all.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

object LetNormalizer {
  def normalizeLet[
      F[_]: Sync: NormalizerRec: BoundVarScope: FreeVarScope: NestingInfoWriter,
      T: BoundVarWriter: FreeVarReader
  ](p: PLet): F[ParN] =
    p.decls_ match {

      case concDeclsImpl: ConcDeclsImpl =>
        def extractNamesAndProcs(decl: Decl): (ListName, NameRemainder, ListProc) =
          decl match {
            case declImpl: DeclImpl =>
              (declImpl.listname_, declImpl.nameremainder_, declImpl.listproc_)

          }

        val (listNames, listNameRemainders, listProcs) =
          (extractNamesAndProcs(p.decl_) :: concDeclsImpl.listconcdecl_.asScala.toList.map {
            case concDeclImpl: ConcDeclImpl => extractNamesAndProcs(concDeclImpl.decl_)
          }).unzip3

        /*
         It is not necessary to use UUIDs to achieve concurrent let declarations.
         While there is the possibility for collisions with either variables declared by the user
         or variables declared within this translation, the chances for collision are astronomically
         small (see analysis here: https://towardsdatascience.com/are-uuids-really-unique-57eb80fc2a87).
         A strictly correct approach would be one that performs a ADT rather than an AST translation, which
         was not done here due to time constraints.
         */
        val variableNames = List.fill(listNames.size)(UUID.randomUUID().toString)

        val psends = variableNames.zip(listProcs).map {
          case (variableName, listProc) =>
            new PSend(new NameVar(variableName), new SendSingle(), listProc)
        }

        val pinput = {
          val listLinearBind = new ListLinearBind()
          variableNames
            .zip(listNames)
            .zip(listNameRemainders)
            .map {
              case ((variableName, listName), nameRemainder) =>
                new LinearBindImpl(
                  listName,
                  nameRemainder,
                  new SimpleSource(new NameVar(variableName))
                )
            }
            .foreach(listLinearBind.add)
          val listReceipt = new ListReceipt()
          listReceipt.add(new ReceiptLinear(new LinearSimple(listLinearBind))).void()
          new PInput(listReceipt, p.proc_)
        }

        val ppar = {
          val procs = psends :+ pinput
          procs.drop(2).foldLeft(new PPar(procs.head, procs(1))) {
            case (ppar, proc) =>
              new PPar(ppar, proc)
          }
        }

        val pnew = {
          val listNameDecl = new ListNameDecl()
          variableNames.map(new NameDeclSimpl(_)).foreach(listNameDecl.add)
          new PNew(listNameDecl, ppar)
        }
        NormalizerRec[F].normalize(pnew)

      case _ =>
        /*
        Let processes with a single bind or with sequential binds ";" are converted into match processes rather
        than input processes, so that each sequential bind doesn't add a new unforgeable name to the tuplespace.
        The Rholang 1.1 spec defines them as the latter. Because the Rholang 1.1 spec defines let processes in terms
        of a output process in concurrent composition with an input process, the let process appears to quote the
        process on the RHS of "<-" and bind it to the pattern on LHS. For example, in
            let x <- 1 in { Nil }
        the process (value) "1" is quoted and bound to "x" as a name. There is no way to perform an AST transformation
        of sequential let into a match process and still preserve these semantics, so we have to do an ADT transformation.
         */
        def convertDecls(d: Decls): Proc = d match {
          case _: EmptyDeclImpl => p.proc_
          case linearDeclsImpl: LinearDeclsImpl =>
            val newDecl =
              linearDeclsImpl.listlineardecl_.asScala.head match {
                case impl: LinearDeclImpl => impl.decl_
              }
            val newDecls =
              if (linearDeclsImpl.listlineardecl_.size == 1)
                new EmptyDeclImpl()
              else {
                val newListLinearDecls = new ListLinearDecl()
                linearDeclsImpl.listlineardecl_.asScala.tail.foreach(newListLinearDecls.add)
                new LinearDeclsImpl(newListLinearDecls)
              }
            new PLet(newDecl, newDecls, p.proc_)
        }

        p.decl_ match {
          case declImpl: DeclImpl =>
            for {
              values <- declImpl.listproc_.asScala.toList.traverse(NormalizerRec[F].normalize)
              normalizePattern = for {
                rem <- NormalizerRec[F].normalize(declImpl.nameremainder_)
                ps  <- declImpl.listname_.asScala.toList.traverse(NormalizerRec[F].normalize)
              } yield EListN(ps, rem)
              patternTuple <- normalizePattern.withinPatternGetFreeVars()

              (patterns, patternFreeVars) = patternTuple

              // Normalize body in the copy of bound scope with added free variables as bounded
              continuation <- NormalizerRec[F]
                               .normalize(convertDecls(p.decls_))
                               .withAbsorbedFreeVars(patternFreeVars)
            } yield MatchN(
              target = EListN(values),
              cases = Seq(
                MatchCaseN(
                  patterns,
                  continuation,
                  patternFreeVars.size
                )
              )
            )
        }
    }
}
