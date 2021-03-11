package coop.rchain.rspace.history

import cats.Parallel
import cats.effect.Concurrent
import cats.syntax.all._
import coop.rchain.metrics.Span
import coop.rchain.rspace.channelStore.ChannelStore
import coop.rchain.rspace.channelStore.instances.ChannelStoreImpl
import coop.rchain.rspace.internal._
import coop.rchain.rspace.merger.StateMerger
import coop.rchain.rspace.state.instances.{RSpaceExporterStore, RSpaceImporterStore}
import coop.rchain.rspace.state.{RSpaceExporter, RSpaceImporter}
import coop.rchain.rspace.{Blake2b256Hash, HotStoreAction, HotStoreTrieAction, InMemRSpaceCache}
import coop.rchain.shared.{Log, Serialize}
import coop.rchain.store.{KeyValueStore, LazyAdHocKeyValueCache}
import scodec.Codec

/**
  * Pointer to data in history (Datums, Continuations or Joins)
  * @param state - state hash
  * @param hash - hash of a leaf
  */
final case class HistoryPointer(state: Blake2b256Hash, hash: Blake2b256Hash)

/**
  * Cache of decoded values from history
  */
final case class HistoryCache[F[_], C, P, A, K](
    dtsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichDatum[A]]],
    wksCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichKont[P, K]]],
    jnsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[RichJoin[C]]]
)

trait HistoryRepository[F[_], C, P, A, K] extends ChannelStore[F, C] {
  def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]]

  def doCheckpoint(actions: Seq[HotStoreTrieAction]): F[HistoryRepository[F, C, P, A, K]]

  def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]]

  def history: History[F]

  def exporter: F[RSpaceExporter[F]]

  def importer: F[RSpaceImporter[F]]

  def stateMerger: StateMerger[F]

  def getHistoryReader(stateHash: Blake2b256Hash): HashHistoryReader[F, C, P, A, K]

  def root: Blake2b256Hash
}

object HistoryRepositoryInstances {

  def lmdbRepository[F[_]: Concurrent: Parallel: Log: Span, C, P, A, K](
      historyKeyValueStore: KeyValueStore[F],
      rootsKeyValueStore: KeyValueStore[F],
      coldKeyValueStore: KeyValueStore[F],
      channelKeyValueStore: KeyValueStore[F],
      rSpaceCache: HistoryCache[F, C, P, A, K]
  )(
      implicit codecC: Codec[C],
      codecP: Codec[P],
      codecA: Codec[A],
      codecK: Codec[K],
      sc: Serialize[C]
  ): F[HistoryRepository[F, C, P, A, K]] = {
    // Roots store
    val rootsRepository = new RootRepository[F](
      RootsStoreInstances.rootsStore[F](rootsKeyValueStore)
    )
    for {
      currentRoot <- rootsRepository.currentRoot()
      // History store
      historyStore = HistoryStoreInstances.historyStore[F](historyKeyValueStore)
      history      = HistoryInstances.merging(currentRoot, historyStore)
      // Cold store
      coldStore = ColdStoreInstances.coldStore[F](coldKeyValueStore)
      // RSpace importer/exporter / directly operates on Store (lmdb)
      exporter     = RSpaceExporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
      importer     = RSpaceImporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
      channelStore = ChannelStoreImpl(channelKeyValueStore, sc, codecC)
    } yield HistoryRepositoryImpl[F, C, P, A, K](
      history,
      rootsRepository,
      coldStore,
      exporter,
      importer,
      channelStore,
      sc,
      rSpaceCache
    )
  }
}
