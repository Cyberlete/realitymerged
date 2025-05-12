package org.reality.sdk.infrastructure.healthcheck.ping

import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.healthcheck.consensus.types.{ConsensusHealthStatus, HealthCheckRoundId}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class PingConsensusHealthStatus(
  key: PingHealthCheckKey,
  roundIds: Set[HealthCheckRoundId],
  owner: PeerId,
  status: PingHealthCheckStatus,
  clusterState: Set[PeerId]
) extends ConsensusHealthStatus[PingHealthCheckKey, PingHealthCheckStatus]
