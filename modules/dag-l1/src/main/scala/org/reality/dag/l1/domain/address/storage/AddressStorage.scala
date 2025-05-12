package org.reality.dag.l1.domain.address.storage

import org.reality.schema.address.Address
import org.reality.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]
  def updateBalances(addressBalances: Map[Address, Balance]): F[Unit]
  def clean: F[Unit]
}
