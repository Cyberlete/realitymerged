package org.reality.combined.node

import io.circe.Decoder
import io.circe.generic.semiauto._

case class L0NodeConfig(
  env: String,
  keyHex: String,
  ip: String,
  publicPort: String,
  p2pPort: String,
  cliPort: String,
  collateral: String,
  snapshotStoredPath: String
) extends BaseNodeConfig

object L0NodeConfig {
  implicit val decoder: Decoder[L0NodeConfig] = deriveDecoder[L0NodeConfig]
}
