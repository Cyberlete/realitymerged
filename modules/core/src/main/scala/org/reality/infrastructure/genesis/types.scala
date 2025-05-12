package org.reality.infrastructure.genesis

import org.reality.schema.address.{Address, NETAddressRefined}
import org.reality.schema.balance.Balance

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  @derive(eqv, show)
  case class GenesisAccount(address: Address, balance: Balance)

  case class GenesisCSVAccount(address: String, balance: Long) {

    def toGenesisAccount: Either[String, GenesisAccount] =
      for {
        netAddress <- refineV[NETAddressRefined](address)
        nonNegLong <- refineV[NonNegative](balance)
      } yield GenesisAccount(Address(netAddress), Balance(nonNegLong))
  }

  object GenesisCSVAccount {
    implicit val rowDecoder: RowDecoder[GenesisCSVAccount] = deriveRowDecoder
  }
}
