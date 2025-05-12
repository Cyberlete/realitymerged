package org.reality.sdk.domain.cluster.services

import org.reality.schema.cluster.ClusterSessionToken
import org.reality.schema.peer._
import org.reality.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info: F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
