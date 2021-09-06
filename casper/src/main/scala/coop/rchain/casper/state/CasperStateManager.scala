package coop.rchain.casper.state

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import coop.rchain.casper.{CasperConf, CasperShardConf}
import coop.rchain.state.StateManager

trait CasperStateManager[F[_]] {
  def getStateRef: Ref[F, CasperState]
  // TODO remove when reading shard config from the chain is ready
  def getShardConf: CasperShardConf
}

final case class CasperStateManagerImpl[F[_]: Sync](
    st: Ref[F, CasperState],
    shardConf: CasperShardConf
) extends CasperStateManager[F] {
  override def getStateRef: Ref[F, CasperState] = st
  override def hasCasper: F[Boolean]            = st.get.map(CasperState.isEmpty)

  override def getShardConf: CasperShardConf = shardConf

}

object CasperStateManager {
  def apply[F[_]](implicit instance: CasperStateManager[F]): CasperStateManager[F] = instance
  def apply[F[_]: Sync](shardConf: CasperShardConf): F[CasperStateManager[F]] =
    Ref.of[F, CasperState](CasperState.empty).map(CasperStateManagerImpl(_, shardConf))
}
