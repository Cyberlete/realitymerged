package org.reality.dag.l1.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.effects.GenUUID
import org.reality.modules.HealthChecks
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

object L1HealthChecks {

  def make[F[_]: Async: GenUUID: Random: Supervisor](
    sdk: SDK[F],
    storages: L1Storages[F],
    services: L1Services[F],
    programs: L1Programs[F],
    p2pClient: L1P2PClient[F],
    //    client: Client[F],
    //    session: Session[F],
    method: CliMethod
  ): F[L1HealthChecks[F]] = {
    def ping = PingHealthCheckConsensus.make(
      storages.cluster,
      programs.joining,
      sdk.nodeId,
      new PingHealthCheckConsensusDriver(),
      method.appConfig.healthCheck,
      services.gossip,
      p2pClient.node,
      sdk.sdkResources.client,
      sdk.sdkServices.session
    )

    ping.map {
      new L1HealthChecks(_) {}
    }
  }
}

abstract class L1HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
) extends HealthChecks[F]
