package org.reality.dag.l1

import cats.effect.IO

import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.modules.{L1HealthChecks, L1HttpApi, L1Programs, L1Queues, L1Services, L1Storages}
import org.reality.sdk.app.{NodeInternals, SDK}
import org.reality.sdk.infrastructure.gossip.GossipDaemon

case class L1Internals(
  p2pClient: L1P2PClient[IO],
  queues: L1Queues[IO],
  storages: L1Storages[IO],
//  validators: Validators[IO],
  services: L1Services[IO],
  programs: L1Programs[IO],
  healthChecks: L1HealthChecks[IO],
  api: L1HttpApi[IO],
  gossipDaemon: GossipDaemon[IO],
  sdk: SDK[IO],
  stateChannel: Option[StateChannel[IO, _, _]]
) extends NodeInternals
