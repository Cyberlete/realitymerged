package org.reality.sdk.domain.collateral

import org.reality.schema.address.Address
import org.reality.schema.balance.Balance

import fs2.Stream

trait LatestBalances[F[_]] {
  def getLatestBalances: F[Option[Map[Address, Balance]]]
  def getLatestBalancesStream: Stream[F, Map[Address, Balance]]
}
