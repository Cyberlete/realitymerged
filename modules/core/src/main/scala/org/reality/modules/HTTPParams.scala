package org.reality.modules

import java.security.KeyPair

import cats.effect.Async

import org.reality.http.p2p.{L0P2PClient, P2PClient}
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.types.HttpConfig
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider

class HTTPParams[F[_]: Async: SecurityProvider: Metrics](
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  key: KeyPair,
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig,
  coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil,
  p2PClient: P2PClient[F]
)

class L0HTTPParams[F[_]: Async: SecurityProvider: Metrics](
  val storages: L0Storages[F],
  val queues: L0Queues[F],
  val services: L0Services[F],
  val programs: L0Programs[F],
  val healthchecks: L0HealthChecks[F],
  val key: KeyPair,
  val environment: AppEnvironment,
  val selfId: PeerId,
  val nodeVersion: String,
  val httpCfg: HttpConfig,
  val coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil,
  val p2pClient: L0P2PClient[F]
)
