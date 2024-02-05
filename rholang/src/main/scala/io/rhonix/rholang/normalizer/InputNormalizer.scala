package io.rhonix.rholang.normalizer

import cats.effect.Sync
import cats.syntax.all.*
import coop.rchain.models.ReceiveBind
import coop.rchain.rholang.interpreter.errors.ReceiveOnSameChannelsError
import io.rhonix.rholang.{*, types}
import io.rhonix.rholang.Bindings.*
import io.rhonix.rholang.ast.rholang.Absyn.*
import io.rhonix.rholang.normalizer.env.*
import io.rhonix.rholang.normalizer.syntax.all.*
import io.rhonix.rholang.types.{ParN, ReceiveBindN, ReceiveN}

import java.util.UUID
import scala.jdk.CollectionConverters.*

object InputNormalizer {
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def normalizeInput[
      F[_]: Sync: NormalizerRec: BoundVarScope: FreeVarScope: NestingInfoWriter,
      T: BoundVarWriter: FreeVarReader
  ](p: PInput): F[ParN] = {
    if (p.listreceipt_.size() > 1) {
      NormalizerRec[F].normalize(
        p.listreceipt_.asScala.reverse.foldLeft(p.proc_) { (proc, receipt) =>
          val listReceipt = new ListReceipt()
          listReceipt.add(receipt)
          new PInput(listReceipt, proc)
        }
      )
    } else {

      val receiptContainsComplexSource: Boolean =
        p.listreceipt_.asScala.head match {
          case rl: ReceiptLinear =>
            rl.receiptlinearimpl_ match {
              case ls: LinearSimple =>
                ls.listlinearbind_.asScala.exists {
                  case lbi: LinearBindImpl =>
                    lbi.namesource_ match {
                      case _: SimpleSource => false
                      case _               => true
                    }

                }
              case _ => false
            }
          case _ => false
        }

      if (receiptContainsComplexSource) {
        p.listreceipt_.asScala.head match {
          case rl: ReceiptLinear =>
            rl.receiptlinearimpl_ match {
              case ls: LinearSimple =>
                val listReceipt    = new ListReceipt()
                val listLinearBind = new ListLinearBind()
                val listNameDecl   = new ListNameDecl()
                listReceipt.add(new ReceiptLinear(new LinearSimple(listLinearBind)))
                val (sends, continuation) =
                  ls.listlinearbind_.asScala.foldLeft((new PNil: Proc, p.proc_)) {
                    case ((sends, continuation), lb) =>
                      lb match {
                        case lbi: LinearBindImpl =>
                          lbi.namesource_ match {
                            case _: SimpleSource =>
                              listLinearBind.add(lbi)
                              (sends, continuation)
                            case _ =>
                              val identifier = UUID.randomUUID().toString
                              val r          = new NameVar(identifier)
                              lbi.namesource_ match {
                                case rss: ReceiveSendSource =>
                                  lbi.listname_.asScala.prepend(r)
                                  listLinearBind.add(
                                    new LinearBindImpl(
                                      lbi.listname_,
                                      lbi.nameremainder_,
                                      new SimpleSource(rss.name_)
                                    )
                                  )
                                  (
                                    sends,
                                    new PPar(
                                      new PSend(r, new SendSingle, new ListProc()),
                                      continuation
                                    )
                                  )
                                case srs: SendReceiveSource =>
                                  listNameDecl.add(new NameDeclSimpl(identifier))
                                  listLinearBind.add(
                                    new LinearBindImpl(
                                      lbi.listname_,
                                      lbi.nameremainder_,
                                      new SimpleSource(r)
                                    )
                                  )
                                  srs.listproc_.asScala.prepend(new PEval(r))
                                  (
                                    new PPar(
                                      new PSend(srs.name_, new SendSingle, srs.listproc_),
                                      sends
                                    ): Proc,
                                    continuation
                                  )
                              }
                          }
                      }
                  }
                val pInput = new PInput(listReceipt, continuation)
                NormalizerRec[F].normalize(
                  if (listNameDecl.isEmpty) pInput
                  else new PNew(listNameDecl, new PPar(sends, pInput))
                )
            }

        }
      } else {

        // To handle the most common case where we can sort the binds because
        // they're from different sources, Each channel's list of patterns starts its free variables at 0.
        // We check for overlap at the end after sorting. We could check before, but it'd be an extra step.
        // We split this into parts. First we process all the sources, then we process all the bindings.

        // If we get to this point, we know p.listreceipt.size() == 1
        val (consumes, persistent, peek) =
          p.listreceipt_.asScala.head match {
            case rl: ReceiptLinear =>
              rl.receiptlinearimpl_ match {
                case ls: LinearSimple =>
                  (
                    ls.listlinearbind_.asScala.toVector.map {
                      case lbi: LinearBindImpl =>
                        (
                          (lbi.listname_.asScala.toVector, lbi.nameremainder_),
                          lbi.namesource_ match {
                            // all sources should be simple sources by this point
                            case ss: SimpleSource => ss.name_
                          }
                        )
                    },
                    false,
                    false
                  )
              }
            case rr: ReceiptRepeated =>
              rr.receiptrepeatedimpl_ match {
                case rs: RepeatedSimple =>
                  (
                    rs.listrepeatedbind_.asScala.toVector.map {
                      case rbi: RepeatedBindImpl =>
                        ((rbi.listname_.asScala.toVector, rbi.nameremainder_), rbi.name_)
                    },
                    true,
                    false
                  )

              }
            case rp: ReceiptPeek =>
              rp.receiptpeekimpl_ match {
                case ps: PeekSimple =>
                  (
                    ps.listpeekbind_.asScala.toVector.map {
                      case pbi: PeekBindImpl =>
                        ((pbi.listname_.asScala.toVector, pbi.nameremainder_), pbi.name_)
                    },
                    false,
                    true
                  )

              }

          }

        val (patterns, names) = consumes.unzip

        def createBinds(
            patterns: Vector[(Vector[Name], NameRemainder)],
            normalizedSources: Vector[ParN]
        ): F[Vector[ReceiveBindN]] =
          (patterns zip normalizedSources)
            .traverse {
              case ((names, remainder), source) =>
                for {
                  initFreeCount <- Sync[F].delay(FreeVarReader[T].getFreeVars.size)
                  rbNames       <- names.traverse(NormalizerRec[F].normalize)
                  rbRemainder   <- NormalizerRec[F].normalize(remainder)
                  freeCount     = FreeVarReader[T].getFreeVars.size - initFreeCount
                } yield ReceiveBindN(rbNames, source, rbRemainder, freeCount)
            }

        def sortBinds(binds: Seq[ReceiveBindN]): F[Seq[ReceiveBindN]] = {
          import coop.rchain.models.rholang.sorter.ReceiveSortMatcher.sortBind
          def from(x: ReceiveBind): ReceiveBindN =
            ReceiveBindN(
              fromProto(x.patterns),
              fromProto(x.source),
              x.remainder.map(fromProtoVar),
              x.freeCount
            )
          def to(x: ReceiveBindN): ReceiveBind =
            ReceiveBind(
              toProto(x.patterns),
              toProto(x.source),
              x.remainder.map(toProtoVar),
              x.freeCount
            )
          binds.traverse(b => sortBind(to(b))).map(_.sorted.map(x => from(x.term)))
        }

        for {
          processedSources <- names.traverse(NormalizerRec[F].normalize)

          patternTuple <- createBinds(patterns, processedSources).withinPatternGetFreeVars(
                           withinReceive = true
                         )

          (unsortedBinds, freeVars) = patternTuple

          // TODO: The sorting will be removed after the old Rholang types are removed.
          //  With the new types, sorting is unnecessary as they are always sorted by hash.
          binds <- sortBinds(unsortedBinds)

          thereAreDuplicatesInSources = processedSources.distinct.size != processedSources.size
          _ <- ReceiveOnSameChannelsError(p.line_num, p.col_num)
                .raiseError[F, Unit]
                .whenA(thereAreDuplicatesInSources)

          // Normalize body in the copy of bound scope with added free variables as bounded
          continuation <- NormalizerRec[F].normalize(p.proc_).withAbsorbedFreeVars(freeVars)

        } yield ReceiveN(binds, continuation, persistent, peek, freeVars.size)
      }
    }
  }
}
