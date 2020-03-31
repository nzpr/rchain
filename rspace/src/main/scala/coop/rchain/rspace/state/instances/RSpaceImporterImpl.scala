package coop.rchain.rspace.state.instances

import java.nio.ByteBuffer

import cats.effect.Concurrent
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.history.Store
import coop.rchain.rspace.state.RSpaceImporter

object RSpaceImporterImpl {
  // RSpace importer constructor / smart constructor "guards" private class
  def apply[F[_]: Concurrent](
      historyStore: Store[F],
      valueStore: Store[F],
      rootsStore: Store[F]
  ): RSpaceImporter[F] =
    RSpaceImporterImpl(historyStore, valueStore, rootsStore)

  private final case class RSpaceImporterImpl[F[_]](
      sourceHistoryStore: Store[F],
      sourceValueStore: Store[F],
      sourceRootsStore: Store[F]
  ) extends RSpaceImporter[F] {
    override def setHistoryItems[Value](
        keys: Seq[(Blake2b256Hash, Value)],
        toBuffer: Value => ByteBuffer
    ): F[RSpaceImporter.ImportResult] = ???

    override def setDataItems[Value](
        keys: Seq[(Blake2b256Hash, Value)],
        toBuffer: Value => ByteBuffer
    ): F[RSpaceImporter.ImportResult] = ???
  }
}
