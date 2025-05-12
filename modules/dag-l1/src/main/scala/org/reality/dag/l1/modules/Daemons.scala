package org.reality.dag.l1.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._
import cats.syntax.traverse._

import org.reality.dag.l1.infrastructure.healthcheck.HealthCheckDaemon
import org.reality.sdk.domain.Daemon
import org.reality.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.reality.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider

object Daemons {

  def start[F[_]: Async: SecurityProvider: Random: Parallel: Metrics: Supervisor](
    storages: L1Storages[F],
    services: L1Services[F],
    healthChecks: L1HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastGlobalSnapshotStorage, storages.cluster),
      HealthCheckDaemon.make(healthChecks)
    ).traverse(_.start).void

}
