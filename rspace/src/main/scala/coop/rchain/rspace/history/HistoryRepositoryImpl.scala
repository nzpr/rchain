package coop.rchain.rspace.history

import java.nio.charset.StandardCharsets

import cats.effect.Sync
import cats.effect.concurrent.{MVar, Ref}
import cats.syntax.all._
import cats.{Applicative, Parallel}
import com.typesafe.scalalogging.Logger
import coop.rchain.crypto.codec.Base16
import coop.rchain.rspace.history.History.KeyPath
import coop.rchain.rspace.history.HistoryRepositoryImpl._
import coop.rchain.rspace.internal._
import coop.rchain.rspace.state.{RSpaceExporter, RSpaceImporter}
import coop.rchain.rspace.trace.{COMM, Consume, Event, IOEvent, Produce}
import coop.rchain.rspace.{
  internal,
  util,
  Blake2b256Hash,
  DeleteContinuations,
  DeleteData,
  DeleteJoins,
  HotStoreAction,
  InsertContinuations,
  InsertData,
  InsertJoins,
  RSpace,
  StableHashProvider
}
import coop.rchain.shared.{Log, Serialize}
import scalapb.GeneratedMessage
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

import scala.collection.concurrent.TrieMap

final case class HistoryRepositoryImpl[F[_]: Sync: Parallel, C, P, A, K](
    history: History[F],
    rootsRepository: RootRepository[F],
    leafStore: ColdStore[F],
    rspaceExporter: RSpaceExporter[F],
    rspaceImporter: RSpaceImporter[F],
    // Map channel hash in event log -> channel hash in history
    // We need to maintain this for event log merge
    channelsHashesMappings: Ref[F, Map[Blake2b256Hash, Set[Blake2b256Hash]]],
    sc: Serialize[C]
)(implicit codecC: Codec[C], codecP: Codec[P], codecA: Codec[A], codecK: Codec[K])
    extends HistoryRepository[F, C, P, A, K] {
  val joinSuffixBits                = BitVector("-joins".getBytes(StandardCharsets.UTF_8))
  val dataSuffixBits                = BitVector("-data".getBytes(StandardCharsets.UTF_8))
  val continuationSuffixBits        = BitVector("-continuation".getBytes(StandardCharsets.UTF_8))
  val codecJoin: Codec[Seq[Seq[C]]] = codecSeq(codecSeq(codecC))

  implicit val serializeC: Serialize[C] = Serialize.fromCodec(codecC)

  private def fetchData(key: Blake2b256Hash): F[Option[PersistedData]] =
    history.find(key.bytes.toSeq.toList).flatMap {
      case (trie, _) =>
        trie match {
          case LeafPointer(dataHash) => leafStore.get(dataHash)
          case EmptyPointer          => Applicative[F].pure(None)
          case _ =>
            Sync[F].raiseError(new RuntimeException(s"unexpected data at key $key, data: $trie"))

        }
    }

  def getDataHashAtPath(key: Blake2b256Hash): F[Option[Blake2b256Hash]] =
    history.find(key.bytes.toSeq.toList).flatMap {
      case (trie, _) =>
        trie match {
          case LeafPointer(dataHash) => Applicative[F].pure(Some(dataHash))
          case EmptyPointer          => Applicative[F].pure(None)
          case _ =>
            Sync[F].raiseError(new RuntimeException(s"unexpected data at key $key, data: $trie"))

        }
    }

  private def hashWithSuffix(bits: BitVector, suffix: BitVector): Blake2b256Hash = {
    val suffixed = bits ++ suffix
    Blake2b256Hash.create(suffixed.toByteVector)
  }

  private def hashJoinsChannel(channel: C): Blake2b256Hash =
    hashWithSuffix(codecC.encode(channel).get, joinSuffixBits)

  private def hashContinuationsChannels(channels: Seq[C]): Blake2b256Hash = {
    val chs = channels
      .map(c => serializeC.encode(c))
      .sorted(util.ordByteVector)
    val channelsBits = codecSeq(codecByteVector).encode(chs).get
    hashWithSuffix(channelsBits, continuationSuffixBits)
  }

  private def hashDataChannel(channel: C): Blake2b256Hash =
    hashWithSuffix(codecC.encode(channel).get, dataSuffixBits)

  def decode[R](bv: ByteVector)(implicit codecR: Codec[R]): Seq[R] =
    Codec.decode[Seq[R]](bv.bits).get.value

  override def getJoins(channel: C): F[Seq[Seq[C]]] =
    fetchData(hashJoinsChannel(channel)).flatMap {
      case Some(JoinsLeaf(bytes)) =>
        decodeJoins[C](bytes).pure[F]
      case Some(p) =>
        Sync[F].raiseError[Seq[Seq[C]]](
          new RuntimeException(
            s"Found unexpected leaf while looking for joins at key $channel, data: $p"
          )
        )
      case None => Seq.empty[Seq[C]].pure[F]
    }

  override def getData(channel: C): F[Seq[internal.Datum[A]]] =
    fetchData(hashDataChannel(channel)).flatMap {
      case Some(DataLeaf(bytes)) =>
        decodeSorted[internal.Datum[A]](bytes).pure[F]
      case Some(p) =>
        Sync[F].raiseError[Seq[internal.Datum[A]]](
          new RuntimeException(
            s"Found unexpected leaf while looking for data at key $channel, data: $p"
          )
        )
      case None => Seq.empty[internal.Datum[A]].pure[F]
    }

  override def getContinuations(channels: Seq[C]): F[Seq[internal.WaitingContinuation[P, K]]] =
    fetchData(hashContinuationsChannels(channels)).flatMap {
      case Some(ContinuationsLeaf(bytes)) =>
        decodeSorted[internal.WaitingContinuation[P, K]](bytes).pure[F]
      case Some(p) =>
        Sync[F].raiseError[Seq[internal.WaitingContinuation[P, K]]](
          new RuntimeException(
            s"Found unexpected leaf while looking for continuations at key $channels, data: $p"
          )
        )
      case None => Seq.empty[internal.WaitingContinuation[P, K]].pure[F]
    }

  type Result = (Blake2b256Hash, Option[PersistedData], HistoryAction)

  protected[this] val dataLogger: Logger =
    Logger("coop.rchain.rspace.datametrics")

  private def measure(actions: List[HotStoreAction]): F[Unit] =
    Sync[F].delay(
      dataLogger.whenDebugEnabled {
        computeMeasure(actions).foreach(p => dataLogger.debug(p))
      }
    )

  private def computeMeasure(actions: List[HotStoreAction]): List[String] =
    actions.map {
      case i: InsertData[C, A] =>
        val key  = hashDataChannel(i.channel).bytes
        val data = encodeData(i.data)
        s"${key.toHex};insert-data;${data.length};${i.data.length}"
      case i: InsertContinuations[C, P, K] =>
        val key  = hashContinuationsChannels(i.channels).bytes
        val data = encodeContinuations(i.continuations)
        s"${key.toHex};insert-continuation;${data.length};${i.continuations.length}"
      case i: InsertJoins[C] =>
        val key  = hashJoinsChannel(i.channel).bytes
        val data = encodeJoins(i.joins)
        s"${key.toHex};insert-join;${data.length}"
      case d: DeleteData[C] =>
        val key = hashDataChannel(d.channel).bytes
        s"${key.toHex};delete-data;0"
      case d: DeleteContinuations[C] =>
        val key = hashContinuationsChannels(d.channels).bytes
        s"${key.toHex};delete-continuation;0"
      case d: DeleteJoins[C] =>
        val key = hashJoinsChannel(d.channel).bytes
        s"${key.toHex};delete-join;0"
    }

  private def transformAndStore(hotStoreActions: List[HotStoreAction]): F[List[HistoryAction]] = {
    import cats.instances.list._
    for {
      transformedActions <- hotStoreActions.traverse(transform)
      historyActions     <- storeLeaves(transformedActions)
    } yield historyActions
  }

  private def transform(action: HotStoreAction): F[Result] = {
    val r = action match {
      case i: InsertData[C, A] =>
        val key = hashDataChannel(i.channel)

        /**
          * Produces are created as
          * new Produce(
          *   hash(channel)(serializeC),   ->   hash is StableHashProvider.hash
          *   hash(channel, datum, persistent),
          *   persistent
          * )
          */
        val eventLogKey = StableHashProvider.hash(i.channel)(sc)
//        val _ = println(
//          s"Channel ${Base16.encode(i.channel.asInstanceOf[GeneratedMessage].toByteString.toByteArray)} hashed to ${eventLogKey} in replay"
//        )
        val data     = encodeData(i.data)
        val dataLeaf = DataLeaf(data)
        val dataHash = Blake2b256Hash.create(data)
        val result: Result =
          (dataHash, Some(dataLeaf), InsertAction(key.bytes.toSeq.toList, dataHash))
        (result, eventLogKey, key)
      case i: InsertContinuations[C, P, K] =>
        val key               = hashContinuationsChannels(i.channels)
        val eventLogKey       = StableHashProvider.hash(i.channels)(sc)
        val data              = encodeContinuations(i.continuations)
        val continuationsLeaf = ContinuationsLeaf(data)
        val continuationsHash = Blake2b256Hash.create(data)
        val result: Result = (
          continuationsHash,
          Some(continuationsLeaf),
          InsertAction(key.bytes.toSeq.toList, continuationsHash)
        )
        (result, eventLogKey, key)
      case i: InsertJoins[C] =>
        val key         = hashJoinsChannel(i.channel)
        val eventLogKey = StableHashProvider.hash(i.channel)(sc)
        val data        = encodeJoins(i.joins)
        val joinsLeaf   = JoinsLeaf(data)
        val joinsHash   = Blake2b256Hash.create(data)
        val result: Result =
          (joinsHash, Some(joinsLeaf), InsertAction(key.bytes.toSeq.toList, joinsHash))
        (result, eventLogKey, key)
      case d: DeleteData[C] =>
        val key            = hashDataChannel(d.channel)
        val eventLogKey    = StableHashProvider.hash(d.channel)(sc)
        val result: Result = (key, None, DeleteAction(key.bytes.toSeq.toList))
        (result, eventLogKey, key)
      case d: DeleteContinuations[C] =>
        val key            = hashContinuationsChannels(d.channels)
        val eventLogKey    = StableHashProvider.hash(d.channels)(sc)
        val result: Result = (key, None, DeleteAction(key.bytes.toSeq.toList))
        (result, eventLogKey, key)
      case d: DeleteJoins[C] =>
        val key            = hashJoinsChannel(d.channel)
        val eventLogKey    = StableHashProvider.hash(d.channel)(sc)
        val result: Result = (key, None, DeleteAction(key.bytes.toSeq.toList))
        (result, eventLogKey, key)
    }
    val (result, eventLogChannelHash, historyChannelHash) = r
//    val _                                                 = println(s"mapping ${eventLogChannelHash} -> ${historyChannelHash}")
    channelsHashesMappings
      .update(map => {
        val oldVal = map.getOrElse(eventLogChannelHash, Set.empty)
        map.updated(eventLogChannelHash, oldVal + historyChannelHash)
      }) >> result
      .pure[F]
  }

  /**
    * Transform events from event log into history actions
    * @param eventsPerStateHash tuple, containing events from particular block and stateHash that has this events applied.
    *                           Typically, stateHash is postStateHash of a block where events are written.
    * @return List of HistoryActions that this HistoryRepository has to apply on intself to get all events recorded.
    */
  def transform(
      eventsPerStateHash: Seq[(Blake2b256Hash, Vector[Event])]
  ): F[List[HistoryAction]] = {
    import cats.instances.list._
    eventsPerStateHash.toList
      .parTraverse { e =>
        val rootThatHasEvents = e._1
        val events            = e._2
        val (produces, consumes, commEvents) =
          events.foldLeft((Vector.empty[Produce], Vector.empty[Consume], Vector.empty[COMM])) {
            (acc, event) =>
              event match {
                case p: Produce => acc.copy(acc._1 :+ p, acc._2, acc._3)
                case c: Consume => acc.copy(acc._1, acc._2 :+ c, acc._3)
                case cm: COMM   => acc.copy(acc._1, acc._2, acc._3 :+ cm)
              }
          }
        val allProduces = produces ++ commEvents.flatMap(_.produces).filterNot(produces.contains)
        val allConsumes = consumes ++ commEvents.map(_.consume).filterNot(consumes.contains)
        // Channels used in computation
        val channelsUsed = allProduces.map(_.channelsHash) ++ allConsumes.flatMap(_.channelsHashes)

        for {
          // get historyRepository instance positioned at root where events should be applied
          historyThatHasEvents <- reset(rootThatHasEvents)

          // get channels that should be changed in HistoryStore (channelsAffected).
          // Some of channels might represents intermediate computation, so we use map maintained by replay
          logChannelToHistoryChannelMap <- channelsHashesMappings.get
          channelsAffected              = channelsUsed.filter(logChannelToHistoryChannelMap.keySet.contains)
          chanAndHistoryKeys = channelsAffected.flatMap(
            chan => logChannelToHistoryChannelMap(chan).map(histKey => (chan, histKey))
          )
          _ = println(
            s"Attempting to apply events from state ${Base16.encode(rootThatHasEvents.toByteString.toByteArray)}." +
              s"(${channelsUsed.size} channels in IOEvents. ${chanAndHistoryKeys.size} corresponding channels (Join, Data, Continuation) in history found in replay map."
          )

          keyPathsAndHashes <- chanAndHistoryKeys
                                .map(_._2)
                                .toList
                                .foldM(Seq.empty[(KeyPath, Option[Blake2b256Hash])]) {
                                  (acc, historyHash) =>
                                    for {
                                      leafHash <- historyThatHasEvents.getDataHashAtPath(
                                                   historyHash
                                                 )
                                    } yield acc :+ (historyHash.bytes.toSeq.toList, leafHash)
                                }
          (existingHashesAtPaths, nonExistingHashesAtPaths) = keyPathsAndHashes.partition(
            _._2.nonEmpty
          )

          deleteActions = nonExistingHashesAtPaths.map(keyNhash => DeleteAction(keyNhash._1))

          insertActions = existingHashesAtPaths.toList.map { keyNhash =>
            {
              val dataHash = keyNhash._2.get
              val keyPath  = keyNhash._1
              InsertAction(keyPath, dataHash)
//                            for {
//                              // TODO Probably dataHash will be changed because we might have some conflicts (mergeable ones, but that required to modify data in cold store)
//                              data <- leafStore.get(dataHash)
//                              dataIsEmpty <- data match {
//                                              case Some(JoinsLeaf(data)) => {
//                                                val joins = decodeJoins[C](data)
//                                                (joins.isEmpty).pure[F]
//                                              }
//                                              case Some(DataLeaf(data)) => {
//                                                val datum = decodeSorted[Datum[A]](data)
//                                                (datum.isEmpty).pure[F]
//                                              }
//                                              case Some(ContinuationsLeaf(data)) => {
//                                                val continuations =
//                                                  decodeSorted[internal.WaitingContinuation[P, K]](
//                                                    data
//                                                  )
//                                                (continuations.isEmpty).pure[F]
//                                              }
//                                              case None =>
//                                                Sync[F].raiseError[Boolean](
//                                                  new RuntimeException(
//                                                    s"Hash ${dataHash} in does not contain valid data in coldStore."
//                                                  )
//                                                )
//                                            }
//                              r = if (dataIsEmpty) {
//                                val _ = println(s"InsertAction expected, but data is empty")
//                                DeleteAction(keyPath)
//                              } else InsertAction(keyPath, dataHash)
//                            } yield r
            }
          }
          _ = println(
            s"${deleteActions.size} deleteActions and ${insertActions.size} insert actions created"
          )
        } yield (deleteActions ++ insertActions)
      }
      .map(_.flatten.groupBy(_.key).values.map(_.head).toList)
  }

  private def storeLeaves(leafs: List[Result]): F[List[HistoryAction]] = {
    val toBeStored = leafs.collect { case (key, Some(data), _) => (key, data) }
    leafStore.put(toBeStored).map(_ => leafs.map(_._3))
  }

  override def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]] = {
    import cats.instances.list._
    val batchSize = Math.max(1, actions.size / RSpace.parallelism)
    for {
      batches        <- Sync[F].delay(actions.grouped(batchSize).toList)
      historyActions <- batches.parFlatTraverse(transformAndStore)
      next           <- history.process(historyActions)
      _              <- rootsRepository.commit(next.root)
      _              <- measure(actions)
    } yield this.copy(history = next)
  }

  override def applyEventsAndCheckpoint(
      events: Seq[(Blake2b256Hash, Vector[Event])]
  ): F[HistoryRepository[F, C, P, A, K]] =
    for {
      actions <- transform(events)
      next    <- history.process(actions)
      _       <- rootsRepository.commit(next.root)
    } yield this.copy(history = next)

  override def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]] =
    for {
      _    <- rootsRepository.validateAndSetCurrentRoot(root)
      next = history.reset(root = root)
    } yield this.copy(history = next)

  override def close(): F[Unit] =
    for {
      _ <- leafStore.close()
      _ <- rootsRepository.close()
      _ <- history.close()
    } yield ()

  override def exporter: F[RSpaceExporter[F]] = Sync[F].delay(rspaceExporter)

  override def importer: F[RSpaceImporter[F]] = Sync[F].delay(rspaceImporter)
}

object HistoryRepositoryImpl {
  val codecSeqByteVector: Codec[Seq[ByteVector]] = codecSeq(codecByteVector)

  private def decodeSorted[D](data: ByteVector)(implicit codec: Codec[D]): Seq[D] =
    codecSeqByteVector.decode(data.bits).get.value.map(bv => codec.decode(bv.bits).get.value)

  private def encodeSorted[D](data: Seq[D])(implicit codec: Codec[D]): ByteVector =
    codecSeqByteVector
      .encode(
        data
          .map(d => Codec.encode[D](d).get.toByteVector)
          .sorted(util.ordByteVector)
      )
      .get
      .toByteVector

  def encodeData[A](data: Seq[internal.Datum[A]])(implicit codec: Codec[Datum[A]]): ByteVector =
    encodeSorted(data)

  def encodeContinuations[P, K](
      continuations: Seq[internal.WaitingContinuation[P, K]]
  )(implicit codec: Codec[WaitingContinuation[P, K]]): ByteVector =
    encodeSorted(continuations)

  def decodeJoins[C](data: ByteVector)(implicit codec: Codec[C]): Seq[Seq[C]] =
    codecSeqByteVector
      .decode(data.bits)
      .get
      .value
      .map(
        bv => codecSeqByteVector.decode(bv.bits).get.value.map(v => codec.decode(v.bits).get.value)
      )

  def encodeJoins[C](joins: Seq[Seq[C]])(implicit codec: Codec[C]): ByteVector =
    codecSeqByteVector
      .encode(
        joins
          .map(
            channels => encodeSorted(channels)
          )
          .sorted(util.ordByteVector)
      )
      .get
      .toByteVector
}
