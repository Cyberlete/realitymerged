package org.reality.sdk.domain.trust.storage

import org.reality.schema.peer.PeerId
import org.reality.schema.trust.TrustInfo

trait TrustStorage[F[_]] {
  def getInfluenceCache: F[Map[PeerId, TrustInfo]]
  def updatePredictedTrust(newTrust: Map[PeerId, Double], thisNode: PeerId): F[Unit]
  def cleanCache(): F[Unit]
  def updateInfluenceCache(entropyRates: Map[PeerId, Double], height: Long): F[Unit]
}
