package org.reality.combined.node

trait BaseNodeConfig {
  def env: String
  def keyHex: String
  def ip: String
  def publicPort: String
  def p2pPort: String
  def cliPort: String
  def collateral: String
}
