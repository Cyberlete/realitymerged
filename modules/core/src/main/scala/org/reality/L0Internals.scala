package org.reality

import cats.effect.IO

import org.reality.http.p2p.L0P2PClient
import org.reality.modules._
import org.reality.sdk.app.{NodeInternals, SDK}
import org.reality.sdk.infrastructure.gossip.GossipDaemon

case class L0Internals(
  p2pClient: L0P2PClient[IO],
  queues: L0Queues[IO],
  storages: L0Storages[IO],
  services: L0Services[IO],
  programs: L0Programs[IO],
  healthChecks: L0HealthChecks[IO],
  api: HttpApi[IO],
  gossipDaemon: GossipDaemon[IO],
  sdk: SDK[IO]
) extends NodeInternals
