package org.reality.sdk.infrastructure.healthcheck.ping

import org.reality.schema._
import org.reality.schema.cluster.SessionToken
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.healthcheck.consensus.types.HealthCheckKey

import com.comcast.ip4s.{Host, Port}
import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class PingHealthCheckKey(id: PeerId, ip: Host, p2pPort: Port, session: SessionToken) extends HealthCheckKey
