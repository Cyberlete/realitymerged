package org.reality.sdk.domain.healthcheck.consensus.types

import org.reality.schema.peer.PeerId

trait HealthCheckKey {
  def id: PeerId
}
