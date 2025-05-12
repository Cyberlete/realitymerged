package org.reality.combined.node

import io.circe.Decoder
import io.circe.generic.semiauto._

case class PeerInfo(
  id: String,
  host: String,
  publicPort: String,
  p2pPort: String
) extends BasicConnectionInfo

object PeerInfo {
  implicit val decoder: Decoder[PeerInfo] = deriveDecoder[PeerInfo]
}
