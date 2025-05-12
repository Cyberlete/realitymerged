package org.reality.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.reality.infrastructure.healthcheck.HealthCheckDaemon
import org.reality.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import org.reality.infrastructure.snapshot.daemon.DownloadDaemon
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.types.AppConfig
import org.reality.sdk.domain.Daemon
import org.reality.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.reality.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.reality.sdk.infrastructure.trust.TrustDaemon

object Daemons {

  def start[F[_]: Async: Supervisor](
    storages: L0Storages[F],
    services: L0Services[F],
    programs: L0Programs[F],
    queues: L0Queues[F],
    healthChecks: L0HealthChecks[F],
    nodeId: PeerId,
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      TrustDaemon.make(cfg.trust.daemon, storages.cluster, storages.trust, nodeId),
      HealthCheckDaemon.make(healthChecks),
      GlobalSnapshotEventsPublisherDaemon.make(queues.stateChannelOutput, queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster)
    ).traverse(_.start).void

}
