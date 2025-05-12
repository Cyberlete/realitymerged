package org.reality.dag.block.processing

import org.reality.schema.BlockReference
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance
import org.reality.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTxRefs: Map[Address, TransactionReference],
  parentUsages: Map[BlockReference, NonNegLong]
)

object BlockAcceptanceContextUpdate {

  val empty: BlockAcceptanceContextUpdate = BlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty,
    Map.empty
  )
}
