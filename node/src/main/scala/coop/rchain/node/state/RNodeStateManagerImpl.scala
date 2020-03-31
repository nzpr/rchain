package coop.rchain.node.state

import java.nio.file.Path

import cats.effect.Sync
import coop.rchain.state.{StateManager, Synchronizer, TrieExporter}

object RNodeStateManagerImpl {
  def apply[F[_]: Sync](exporter: TrieExporter[F]): StateManager[F] =
    RNodeStateManagerImpl[F](exporter)

  private final case class RNodeStateManagerImpl[F[_]: Sync](exporter: TrieExporter[F])
      extends StateManager[F] {
    override def createSync(dirPath: Path): F[Synchronizer[F]] = ???
  }
}
