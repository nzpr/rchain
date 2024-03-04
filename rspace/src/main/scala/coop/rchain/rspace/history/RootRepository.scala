package coop.rchain.rspace.history

import cats.Applicative
import cats.syntax.all._
import cats.effect.Sync
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.history.History.emptyRootHash

import scala.Function._

class RootRepository[F[_]: Sync](
    rootsStore: RootsStore[F]
) {

  def commit(root: Blake2b256Hash): F[Unit] =
    rootsStore.recordRoot(root)

  def currentRoot(): F[Blake2b256Hash] =
    rootsStore.currentRoot().flatMap {
      case None       => rootsStore.recordRoot(emptyRootHash).map(const(emptyRootHash))
      case Some(root) => Applicative[F].pure(root)
    }

  def validateAndSetCurrentRoot(root: Blake2b256Hash): F[Unit] =
    rootsStore.validateAndSetCurrentRoot(root).flatMap {
      case None    => new RuntimeException(s"unknown root $root").raiseError[F, Unit]
      case Some(_) => Applicative[F].pure(())
    }

}
