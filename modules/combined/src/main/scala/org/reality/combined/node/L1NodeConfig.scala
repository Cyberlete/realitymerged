package org.reality.combined.node

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

case class L1NodeConfig(
  env: String,
  keyHex: String,
  ip: String,
  publicPort: String,
  p2pPort: String,
  cliPort: String,
  collateral: String,
  aciDbHomePath: String,
  l0PeerId: String,
  l0PeerHost: String,
  l0PeerPort: String
) extends BaseNodeConfig

object L1NodeConfig {
  implicit val decoder: Decoder[L1NodeConfig] = deriveDecoder
}
