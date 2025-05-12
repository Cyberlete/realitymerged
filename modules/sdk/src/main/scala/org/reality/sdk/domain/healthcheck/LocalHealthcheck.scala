package org.reality.sdk.domain.healthcheck

import org.reality.schema.peer.{Peer, PeerId}

trait LocalHealthcheck[F[_]] {
  def start(peer: Peer): F[Unit]
  def cancel(peerId: PeerId): F[Unit]
}
