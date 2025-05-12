package org.reality.sdk.domain.cluster.storage

import org.reality.schema.cluster.SessionToken

trait SessionStorage[F[_]] {
  def createToken: F[SessionToken]
  def getToken: F[Option[SessionToken]]
  def clearToken: F[Unit]
}
