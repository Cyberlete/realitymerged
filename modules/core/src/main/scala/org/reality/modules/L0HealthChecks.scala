package org.reality.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.reality.effects.GenUUID
import org.reality.http.p2p.L0P2PClient
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

object L0HealthChecks {

  def make[F[_]: Async: GenUUID: Random: Supervisor](
    sdk: SDK[F],
    storages: L0Storages[F],
    services: L0Services[F],
    programs: L0Programs[F],
    p2pClient: L0P2PClient[F],
//    client: Client[F],
//    session: Session[F],
    method: CliMethod
  ): F[L0HealthChecks[F]] = {
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
      new L0HealthChecks(_) {}
    }
  }
}
abstract class L0HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
) extends HealthChecks[F]

abstract class HealthChecks[F[_]]
