package org.reality.sdk.infrastructure.trust

import cats.effect.std.Supervisor
import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.schema.peer
import org.reality.schema.peer.PeerId
import org.reality.schema.trust.TrustInfo
import org.reality.sdk.config.types.TrustDaemonConfig
import org.reality.sdk.domain.Daemon
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.trust.storage.TrustStorage

trait TrustDaemon[F[_]] extends Daemon[F]

object TrustDaemon {

  def make[F[_]: Async](
    cfg: TrustDaemonConfig,
    clusterStorage: ClusterStorage[F],
    trustStorage: TrustStorage[F],
    selfPeerId: PeerId
  )(implicit S: Supervisor[F]): TrustDaemon[F] = new TrustDaemon[F] {

    def start: F[Unit] =
      for {
        _ <- S.supervise(modelUpdate.foreverM).void
      } yield ()

    private def calculatePredictedTrust(newPeers: Map[PeerId, TrustInfo])(trust: Map[PeerId, TrustInfo]): Map[PeerId, Double] =
      TrustModel.calculateEntropicTrust(trust ++ newPeers, selfPeerId)

    private def modelUpdate: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        currentPeers: Set[peer.Peer] <- clusterStorage.getPeers
        trustCache: Map[PeerId, TrustInfo] <- trustStorage.getInfluenceCache
        newPeers: Set[PeerId] = currentPeers.map(_.id).diff(trustCache.keySet)
        newTrust: Map[PeerId, Double] <- trustStorage.getInfluenceCache.map(
          calculatePredictedTrust(trustCache ++ newPeers.map(pId => (pId, TrustInfo())).toMap)
        )
        _ <- trustStorage.updatePredictedTrust(newTrust, selfPeerId)
      } yield ()

  }
}
