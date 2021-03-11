package coop.rchain.rspace.history.instances

import cats.Applicative
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.history.ColdStoreInstances.ColdKeyValueStore
import coop.rchain.rspace.history._
import coop.rchain.rspace.internal._
import coop.rchain.shared.syntax._
import coop.rchain.store.LazyAdHocKeyValueCache
import scodec.Codec

class CachingHashHistoryReaderImpl[F[_]: Concurrent, C, P, A, K](
    targetHistory: History[F],
    rSpaceCache: HistoryCache[F, C, P, A, K],
    leafStore: ColdKeyValueStore[F]
)(
    implicit codecC: Codec[C],
    codecP: Codec[P],
    codecA: Codec[A],
    codecK: Codec[K]
) extends HashHistoryReader[F, C, P, A, K] {

  val datumsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichDatum[A]]] =
    rSpaceCache.dtsCache
  val contsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichKont[P, K]]] =
    rSpaceCache.wksCache
  val joinsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichJoin[C]]] =
    rSpaceCache.jnsCache

  /** read methods for datums */
  override def getData(hash: Blake2b256Hash): F[Seq[Datum[A]]] =
    getRichDatums(hash).map(_.map(_.decoded))

  override def getRichDatums(hash: Blake2b256Hash): F[Seq[RichDatum[A]]] =
    datumsCache
      .get(
        HistoryPointer(targetHistory.root, hash),
        fetchData(hash).flatMap {
          case Some(DataLeaf(bytes)) =>
            Sync[F].delay(
              decodeDataRich[A](bytes).map(v => RichDatum(v.item, v.byteVector))
            )
          case Some(p) =>
            Sync[F].raiseError[Seq[RichDatum[A]]](
              new RuntimeException(
                s"Found unexpected leaf while looking for data at key $hash, data: $p"
              )
            )
          case None => Seq.empty[RichDatum[A]].pure
        }
      )

  /** read methods for continuations */
  override def getContinuations(hash: Blake2b256Hash): F[Seq[WaitingContinuation[P, K]]] =
    getRichContinuations(hash).map(_.map(_.decoded))

  // This methods returning raw bytes along with decode value is performnce optimisatoin
  // Making diff for two Seq[(WaitingContinuation[P, K]] is 5-10 tims slower then Seq[ByteVector],
  // so the second val of the tuple is exposed to compare values
  override def getRichContinuations(
      hash: Blake2b256Hash
  ): F[Seq[RichKont[P, K]]] =
    contsCache
      .get(
        HistoryPointer(targetHistory.root, hash),
        fetchData(hash).flatMap {
          case Some(ContinuationsLeaf(bytes)) =>
            Sync[F].delay(
              decodeContinuationsRich[P, K](bytes)
                .map(v => RichKont(v.item, v.byteVector))
            )
          case Some(p) =>
            Sync[F].raiseError[Seq[RichKont[P, K]]](
              new RuntimeException(
                s"Found unexpected leaf while looking for continuations at key $hash, data: $p"
              )
            )
          case None => Seq.empty[RichKont[P, K]].pure
        }
      )

  /** read methods for joins */
  override def getJoins(hash: Blake2b256Hash): F[Seq[Seq[C]]] =
    getRichJoins(hash).map(_.map(_.decoded))

  override def getRichJoins(hash: Blake2b256Hash): F[Seq[RichJoin[C]]] =
    joinsCache
      .get(
        HistoryPointer(targetHistory.root, hash),
        fetchData(hash).flatMap {
          case Some(JoinsLeaf(bytes)) =>
            Sync[F].delay(
              decodeJoinsRich[C](bytes).map(v => RichJoin(v.item, v.byteVector))
            )
          case Some(p) =>
            Sync[F].raiseError[Seq[RichJoin[C]]](
              new RuntimeException(
                s"Found unexpected leaf while looking for join at key $hash, data: $p"
              )
            )
          case None => Seq.empty[RichJoin[C]].pure
        }
      )

  /** Fetch data on a hash pointer */
  def fetchData(
      key: Blake2b256Hash
  ): F[Option[PersistedData]] =
    targetHistory.find(key.bytes.toSeq.toList).flatMap {
      case (trie, _) =>
        trie match {
          case LeafPointer(dataHash) => leafStore.get(dataHash)
          case EmptyPointer          => Applicative[F].pure(None)
          case _ =>
            Sync[F].raiseError(new RuntimeException(s"unexpected data at key $key, data: $trie"))

        }
    }

  override def root: Blake2b256Hash = targetHistory.root
}
