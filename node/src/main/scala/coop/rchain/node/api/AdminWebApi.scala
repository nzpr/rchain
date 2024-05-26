package coop.rchain.node.api

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.casper.api.BlockApi

trait AdminWebApi[F[_]] {
  def propose: F[String]
  def proposeResult: F[String]
  def vDag(depth: Int, startBlockNumber: Int, showJs: Boolean): F[String]
  def replay(hash: String): F[String]
}

object AdminWebApi {
  class AdminWebApiImpl[F[_]: Sync](blockApi: BlockApi[F]) extends AdminWebApi[F] {
    import WebApiSyntax._

    def propose: F[String] =
      blockApi.createBlock(isAsync = false).flatMap(_.liftToBlockApiErr)

    def proposeResult: F[String] =
      blockApi.getProposeResult.flatMap(_.liftToBlockApiErr)

    override def vDag(depth: Int, startBlockNumber: Int, showJs: Boolean): F[String] =
      blockApi
        .visualizeDag(depth, startBlockNumber, showJs)
        .flatMap(_.liftToBlockApiErr)
        .map(_.mkString)

    override def replay(hash: String): F[String] =
      blockApi.replay(hash).flatMap(_.liftToBlockApiErr)
  }
}
