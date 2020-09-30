package coop.rchain.rspace.state

import cats.effect._
import cats.syntax.all._
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.history.{ColdStoreInstances, PersistedData, Trie}
import coop.rchain.shared.AttemptOps._
import coop.rchain.state.TrieImporter
import scodec.Codec
import scodec.bits.ByteVector

trait RSpaceImporter[F[_]] extends TrieImporter[F] {
  type KeyHash = Blake2b256Hash

  def getHistoryItem(hash: KeyHash): F[Option[ByteVector]]
}

final case class StateValidationError(message: String) extends Exception(message)

object RSpaceImporter {
  def validateStateItems[F[_]: Sync](
      historyItems: Seq[(Blake2b256Hash, BitVector)],
      dataItems: Seq[(Blake2b256Hash, BitVector)],
      startPath: Seq[(Blake2b256Hash, Option[Byte])],
      chunkSize: Int,
      skip: Int,
      getFromHistory: Blake2b256Hash => F[Option[BitVector]]
  ): F[Unit] = {
    import cats.instances.list._

    val receivedHistorySize = historyItems.size
    val receivedDataSize    = dataItems.size
    val isEnd               = receivedHistorySize < chunkSize

    def raiseError[A](msg: String): F[A] = StateValidationError(msg).raiseError[F, A]

    def decodeTrie(bytes: BitVector): Trie = Trie.codecTrie.decodeValue(bytes).get

    def decodeData(bytes: BitVector): PersistedData =
      ColdStoreInstances.codecPersistedData.decodeValue(bytes).get

    // Validate history items size
    def validateHistorySize[A]: F[Unit] = {
      val sizeIsValid = receivedHistorySize == chunkSize | isEnd
      raiseError[A](
        s"Input size of history items is not valid. Expected chunk size $chunkSize, received $receivedHistorySize."
      ).whenA(!sizeIsValid)
    }

    // Validate data items size
    def validateDataSize[A]: F[Unit] = {
      val sizeIsValid = receivedHistorySize == chunkSize | isEnd
      raiseError[A](
        s"Input size of data items $receivedDataSize is greater then expected chunk size $chunkSize."
      ).whenA(!sizeIsValid)
    }

    // Tries decoded from received items
    // - validate trie hash to match decoded trie hash
    def tries: F[List[(Blake2b256Hash, Trie)]] = historyItems.toList traverse {
      case (hash, trieBytes) =>
        val trie = decodeTrie(trieBytes)
        // TODO: this is strange that we need to encode Trie again to get the hash.
        //  - the reason is that hash is not calculated on result of `Trie.codecTrie.encode`
        //    but on each case of Trie instance (see `Trie.hash`)
        //  - this adds substantial time for validation e.g. 20k records 450ms (with encoding 2.4sec)
        // https://github.com/rchain/rchain/blob/4dd216a7/rspace/src/main/scala/coop/rchain/rspace/history/HistoryStore.scala#L25-L26
        val trieHash = Trie.hash(trie)
        if (hash == trieHash) (hash, trie).pure[F]
        else
          raiseError(
            s"Trie hash does not match decoded trie, key: ${hash.bytes.toHex}, decoded: ${trieHash.bytes.toHex}."
          )
    }

    // Validate data hashes
    def validateDataItemsHashes: F[Unit] = dataItems.toList traverse_ {
      case (hash, bytes) =>
        val persistedData = decodeData(bytes)
        val dataHash      = Blake2b256Hash.create(persistedData.bytes)
        if (hash == dataHash) ().pure[F]
        else
          raiseError[Unit](
            s"Data hash does not match decoded data, key: ${hash.bytes.toHex}, decoded: ${dataHash.bytes.toHex}."
          )
    }

    def trieHashNotFoundError(h: Blake2b256Hash) =
      StateValidationError(
        s"Trie hash not found in received items or in history store, hash: ${h.bytes.toHex}."
      )

    // Find Trie by hash. Trie must be found in received history items or in previously imported items.
    def getTrie(st: Map[Blake2b256Hash, Trie])(hash: Blake2b256Hash) = {
      val trieOpt = st.get(hash)
      trieOpt.fold {
        for {
          bytesOpt <- getFromHistory(hash)
          bytes    <- bytesOpt.liftTo(trieHashNotFoundError(hash))
          trie     = decodeTrie(bytes)
        } yield trie
      }(_.pure[F])
    }

    for {
      // Validate chunk size.
      _ <- validateHistorySize
      _ <- validateDataSize

      // Decode tries from received history items.
      trieMap <- tries map (_.toMap)

      // Traverse trie and extract nodes / the same as in export. Nodes must match hashed keys.
      nodes <- RSpaceExporter.traverseTrie(startPath, skip, chunkSize, getTrie(trieMap))

      // Validate data (leaf) items hashes.
      _ <- validateDataItemsHashes

      // Extract history and data keys.
      (leafs, nonLeafs) = nodes.partition(_.isLeaf)
      historyKeys       = nonLeafs.map(_.hash)
      dataKeys          = leafs.map(_.hash)

      // Validate keys / cryptographic proof that store chunk is not corrupted or modified.
      historyKeysMatch = historyItems.map(_._1) == historyKeys
      _                <- raiseError(s"History items are corrupted.").whenA(!historyKeysMatch)

      dataKeysMatch = dataItems.map(_._1) == dataKeys
      _             <- raiseError(s"Data items are corrupted.").whenA(!dataKeysMatch)
    } yield ()
  }
}
