package org.reality.domain.net

import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance

trait NETService[F[_]] {
  def getBalances: F[Option[(SnapshotOrdinal, Map[Address, Balance])]]

  def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]]

  def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]

  def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]

  def getWalletCount: F[Option[(Int, SnapshotOrdinal)]]
}
