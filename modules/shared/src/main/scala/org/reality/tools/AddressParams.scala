package org.reality.tools

import java.security.KeyPair

import org.reality.schema.address.Address
import org.reality.schema.transaction.TransactionReference
import org.reality.security.key.ops._

case class AddressParams(
  address: Address,
  keyPair: KeyPair,
  initialTxRef: TransactionReference
)

object AddressParams {

  def apply(keyPair: KeyPair, initialTxRef: TransactionReference): AddressParams =
    AddressParams(keyPair.getPublic.toAddress, keyPair, initialTxRef)
  def apply(keyPair: KeyPair): AddressParams = AddressParams(keyPair, TransactionReference.empty)
}
