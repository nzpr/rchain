package coop.rchain.rspace.history

import java.nio.ByteBuffer
import java.nio.file.Path

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.lmdb.LMDBStore
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.shared.ByteVectorOps.RichByteVector
import org.lmdbjava.DbiFlags.MDB_CREATE
import org.lmdbjava.{Env, EnvFlags, Txn}
import scodec.bits.BitVector

trait Store[F[_]] {
  def get(key: Blake2b256Hash): F[Option[BitVector]]
  def put(key: Blake2b256Hash, value: BitVector): F[Unit]
  def get(key: ByteBuffer): F[Option[ByteBuffer]]
  def put(key: ByteBuffer, value: ByteBuffer): F[Unit]
  def put(data: Seq[(Blake2b256Hash, BitVector)]): F[Unit]
  def close(): F[Unit]
  // Bulk access to raw values
  def get[Value](key: Seq[Blake2b256Hash], fromBuffer: ByteBuffer => Value): F[Seq[Option[Value]]]
  // Store status / entries
  def status: F[Status]
}

final case class StoreConfig(
    path: Path,
    mapSize: Long,
    maxDbs: Int = 2,
    maxReaders: Int = 2048,
    flags: List[EnvFlags] = List(EnvFlags.MDB_NOTLS)
)

final case class Status(
    entries: Long,
    pageSize: Int,
    depth: Int,
    branchPages: Long,
    leafPages: Long,
    overflowPages: Long
)

object StoreInstances {
  def lmdbStore[F[_]: Sync](config: StoreConfig): F[Store[F]] =
    for {
      env <- Sync[F].delay {
              Env
                .create()
                .setMapSize(config.mapSize)
                .setMaxDbs(config.maxDbs)
                .setMaxReaders(config.maxReaders)
                .open(config.path.toFile, config.flags: _*)
            }
      dbi   <- Sync[F].delay { env.openDbi("db", MDB_CREATE) }
      store = LMDBStore(env, dbi)
    } yield new Store[F] {
      override def get(key: Blake2b256Hash): F[Option[BitVector]] = {
        val directKey = key.bytes.toDirectByteBuffer
        get(directKey).map(v => v.map(BitVector(_)))
      }

      override def get[T](
          keys: Seq[Blake2b256Hash],
          fromBuffer: ByteBuffer => T
      ): F[Seq[Option[T]]] = {
        val rawKeys = keys.map(_.bytes.toDirectByteBuffer)
        store.get(rawKeys, fromBuffer)
      }

      override def put(key: Blake2b256Hash, value: BitVector): F[Unit] = {
        val directKey   = key.bytes.toDirectByteBuffer
        val directValue = value.toByteVector.toDirectByteBuffer
        put(directKey, directValue)
      }

      override def get(key: ByteBuffer): F[Option[ByteBuffer]] = store.get(key)

      override def put(key: ByteBuffer, value: ByteBuffer): F[Unit] = store.put(key, value)

      @SuppressWarnings(Array("org.wartremover.warts.Throw"))
      private[this] def putIfAbsent(
          txn: Txn[ByteBuffer],
          key: ByteBuffer,
          value: ByteBuffer
      ): Unit =
        Option(dbi.get(txn, key)) match {
          case None =>
            if (!dbi.put(txn, key, value)) {
              throw new RuntimeException("was not able to put data")
            }
          case _: Some[ByteBuffer] => ()
        }

      override def put(data: Seq[(Blake2b256Hash, BitVector)]): F[Unit] = {
        val byteBuffers = data.map {
          case (key, bytes) =>
            (key.bytes.toDirectByteBuffer, bytes.toByteVector.toDirectByteBuffer)
        }
        store.withWriteTxnF { txn =>
          byteBuffers.foreach { case (key, value) => putIfAbsent(txn, key, value) }
        }
      }

      override def close(): F[Unit] = store.close()

      override def status: F[Status] =
        store.stat.map { s =>
          Status(s.entries, s.pageSize, s.depth, s.branchPages, s.leafPages, s.overflowPages)
        }
    }
}
