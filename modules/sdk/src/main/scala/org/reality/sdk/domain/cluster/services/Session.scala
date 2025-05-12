package org.reality.sdk.domain.cluster.services

import org.reality.schema.cluster.{SessionToken, TokenVerificationResult}
import org.reality.schema.peer.PeerId

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
