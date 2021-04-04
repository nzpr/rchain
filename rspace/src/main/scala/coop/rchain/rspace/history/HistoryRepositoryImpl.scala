package coop.rchain.rspace.history

import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import cats.{Applicative, Parallel}
import com.typesafe.scalalogging.Logger
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.rspace.Hasher.{hashContinuationsChannels, hashDataChannel, hashJoinsChannel}
import coop.rchain.rspace.channelStore.{ChannelHash, ChannelStore}
import coop.rchain.rspace.history.ColdStoreInstances.ColdKeyValueStore
import coop.rchain.rspace.history.instances.CachingHashHistoryReaderImpl
import coop.rchain.rspace.internal._
import coop.rchain.rspace.merger.StateMerger
import coop.rchain.rspace.state.{RSpaceExporter, RSpaceImporter}
import coop.rchain.rspace._
import coop.rchain.rspace.merger.instances.DiffStateMerger
import coop.rchain.shared.{Log, Serialize}
import coop.rchain.shared.syntax._
import coop.rchain.metrics.implicits._
import scodec.Codec
import fs2.Stream

final case class HistoryRepositoryImpl[F[_]: Concurrent: Parallel: Log: Span, C, P, A, K](
    currentHistory: History[F],
    rootsRepository: RootRepository[F],
    leafStore: ColdKeyValueStore[F],
    rspaceExporter: RSpaceExporter[F],
    rspaceImporter: RSpaceImporter[F],
    // Map channel hash in event log -> channel hash in history
    // We need to maintain this for event log merge
    channelHashesStore: ChannelStore[F, C],
    sc: Serialize[C]
)(
    implicit codecC: Codec[C],
    codecP: Codec[P],
    codecA: Codec[A],
    codecK: Codec[K]
) extends HistoryRepository[F, C, P, A, K] {

  implicit val serializeC: Serialize[C] = Serialize.fromCodec(codecC)

  implicit val ms = Metrics.Source(RSpaceMetricsSource, "history")

  override def getChannelHash(hash: Blake2b256Hash): F[Option[ChannelHash]] =
    channelHashesStore.getChannelHash(hash)

  override def putChannelHash(channel: C): F[Unit] = channelHashesStore.putChannelHash(channel)

  override def putContinuationHash(channels: Seq[C]): F[Unit] =
    channelHashesStore.putContinuationHash(channels)

  type CacheAction = Blake2b256Hash => F[Unit]
  type ColdAction  = (Blake2b256Hash, Option[PersistedData])

  type Result = (ColdAction, HistoryAction)

  protected[this] val dataLogger: Logger =
    Logger("coop.rchain.rspace.datametrics")

  private def measure(actions: List[HotStoreAction]): F[Unit] =
    Sync[F].delay(
      dataLogger.whenDebugEnabled {
        computeMeasure(actions).foreach(p => dataLogger.debug(p))
      }
    )

  private def computeMeasure(actions: List[HotStoreAction]): List[String] =
    actions.par.map {
      case i: InsertData[C, A] =>
        val key  = hashDataChannel(i.channel, codecC).bytes
        val data = encodeData(i.data)
        s"${key.toHex};insert-data;${data.length};${i.data.length}"
      case i: InsertContinuations[C, P, K] =>
        val key  = hashContinuationsChannels(i.channels, serializeC).bytes
        val data = encodeContinuations(i.continuations)
        s"${key.toHex};insert-continuation;${data.length};${i.continuations.length}"
      case i: InsertJoins[C] =>
        val key  = hashJoinsChannel(i.channel, codecC).bytes
        val data = encodeJoins(i.joins)
        s"${key.toHex};insert-join;${data.length}"
      case d: DeleteData[C] =>
        val key = hashDataChannel(d.channel, codecC).bytes
        s"${key.toHex};delete-data;0"
      case d: DeleteContinuations[C] =>
        val key = hashContinuationsChannels(d.channels, serializeC).bytes
        s"${key.toHex};delete-continuation;0"
      case d: DeleteJoins[C] =>
        val key = hashJoinsChannel(d.channel, codecC).bytes
        s"${key.toHex};delete-join;0"
    }.toList

  private def storeChannelHash(action: HotStoreAction) =
    action match {
      case i: InsertData[C, A] =>
        channelHashesStore.putChannelHash(i.channel)
      case i: InsertContinuations[C, P, K] =>
        channelHashesStore.putContinuationHash(i.channels)
      case i: InsertJoins[C] =>
        channelHashesStore.putChannelHash(i.channel)
      case d: DeleteData[C] =>
        channelHashesStore.putChannelHash(d.channel)
      case d: DeleteContinuations[C] =>
        channelHashesStore.putContinuationHash(d.channels)
      case d: DeleteJoins[C] =>
        channelHashesStore.putChannelHash(d.channel)
    }

  private def calculateStorageActions(action: HotStoreTrieAction): Result =
    action match {
      case i: TrieInsertProduce[A] =>
        val (data, _) = encodeDataRich(i.data)
        val dataLeaf  = DataLeaf(data)
        val dataHash  = Blake2b256Hash.create(data)
        (
          (dataHash, Some(dataLeaf)),
          (InsertAction(i.hash.bytes.toSeq.toList, dataHash))
        )
      case i: TrieInsertConsume[P, K] =>
        val (data, _)         = encodeContinuationsRich(i.continuations)
        val continuationsLeaf = ContinuationsLeaf(data)
        val continuationsHash = Blake2b256Hash.create(data)
        (
          (continuationsHash, Some(continuationsLeaf)),
          (InsertAction(i.hash.bytes.toSeq.toList, continuationsHash))
        )
      case i: TrieInsertJoins[C] =>
        val (data, _) = encodeJoinsRich(i.joins)
        val joinsLeaf = JoinsLeaf(data)
        val joinsHash = Blake2b256Hash.create(data)
        (
          (joinsHash, Some(joinsLeaf)),
          (InsertAction(i.hash.bytes.toSeq.toList, joinsHash))
        )
      case d: TrieDeleteProduce =>
        ((d.hash, None), (DeleteAction(d.hash.bytes.toSeq.toList)))
      case d: TrieDeleteConsume =>
        ((d.hash, None), (DeleteAction(d.hash.bytes.toSeq.toList)))
      case d: TrieDeleteJoins =>
        ((d.hash, None), (DeleteAction(d.hash.bytes.toSeq.toList)))
    }

  private def transform(hotStoreAction: HotStoreAction): HotStoreTrieAction =
    hotStoreAction match {
      case i: InsertData[C, A] =>
        val key = hashDataChannel(i.channel, codecC)
        TrieInsertProduce(key, i.data)
      case i: InsertContinuations[C, P, K] =>
        val key = hashContinuationsChannels(i.channels, serializeC)
        TrieInsertConsume(key, i.continuations)
      case i: InsertJoins[C] =>
        val key = hashJoinsChannel(i.channel, codecC)
        TrieInsertJoins(key, i.joins)
      case d: DeleteData[C] =>
        val key = hashDataChannel(d.channel, codecC)
        TrieDeleteProduce(key)
      case d: DeleteContinuations[C] =>
        val key = hashContinuationsChannels(d.channels, serializeC)
        TrieDeleteConsume(key)
      case d: DeleteJoins[C] =>
        val key = hashJoinsChannel(d.channel, codecC)
        TrieDeleteJoins(key)
    }

  // this method is what chackpoint is supposed to do, but checkpoint operates on actions on channels, and this
  // address by hashes. TODO elaborate unified API
  def doCheckpoint(
      trieActions: Stream[F, HotStoreTrieAction]
  ): F[HistoryRepository[F, C, P, A, K]] = {
    val storageActions =
      trieActions.parEvalMapProcBounded(a => Sync[F].delay(calculateStorageActions(a)))
    val dataActions    = storageActions.collect { case ((key, Some(data)), _) => (key, data) }
    val historyActions = storageActions.map(_._2)

    // persist data changes
    val processDataActions =
      Span[F].traceI("compute-cold-actions")(dataActions.compile.toList) >>= { r =>
        Span[F].traceI("store-cold-data")(leafStore.putIfAbsent(r))
      }
    // persist history changes, commit resulting new history root
    val processHistoryActions =
      Span[F].traceI("compute-history-actions")(historyActions.compile.toList) >>= { r =>
        Span[F].traceI("store-history-data")(
          history
            .process(r)
            .flatTap(resultHistory => rootsRepository.commit(resultHistory.root))
        )
      }

    // TODO this way to run streams concurrently does not work, second stream will be terminated once first end. We need better way
    val work = Stream.eval(processHistoryActions) concurrently Stream.eval(processDataActions)

    work.compile.lastOrError.map(newHistory => this.copy(currentHistory = newHistory))
  }

  override def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]] = {
    val trieActions       = Stream.emits(actions).parEvalMapProcBounded(a => Sync[F].delay(transform(a)))
    val performCheckpoint = doCheckpoint(trieActions)

    // store channels mapping
    val storeChannels = Span[F].withMarks("populate-channels")(
      Stream
        .emits(actions.map(a => Stream.eval(storeChannelHash(a))))
        .parJoinProcBounded
        .compile
        .drain
    )

    performCheckpoint <* storeChannels <* measure(actions)
  }

  override def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]] =
    for {
      _    <- rootsRepository.validateAndSetCurrentRoot(root)
      next = history.reset(root = root)
    } yield this.copy(currentHistory = next)

  override def exporter: F[RSpaceExporter[F]] = Sync[F].delay(rspaceExporter)

  override def importer: F[RSpaceImporter[F]] = Sync[F].delay(rspaceImporter)

  override def stateMerger: StateMerger[F] = DiffStateMerger[F, C, P, A, K](this, sc)

  override def history: History[F] = currentHistory

  override def root: Blake2b256Hash = currentHistory.root

  override def getHistoryReader(stateHash: Blake2b256Hash): HashHistoryReader[F, C, P, A, K] =
    new CachingHashHistoryReaderImpl(history.reset(root = stateHash), leafStore)
}

object HistoryRepositoryImpl {
  def fetchData[F[_]: Sync](
      key: Blake2b256Hash,
      history: History[F],
      leafStore: ColdKeyValueStore[F]
  ): F[Option[PersistedData]] =
    history.find(key.bytes.toSeq.toList).flatMap {
      case (trie, _) =>
        trie match {
          case LeafPointer(dataHash) => leafStore.get(dataHash)
          case EmptyPointer          => Applicative[F].pure(None)
          case _ =>
            Sync[F].raiseError(new RuntimeException(s"unexpected data at key $key, data: $trie"))

        }
    }
}
