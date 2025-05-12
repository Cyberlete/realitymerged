package org.reality.combined.node

case class ConnectionInfo(
  host: String,
  publicPort: String,
  p2pPort: String
) extends BasicConnectionInfo
