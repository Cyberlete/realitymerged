package org.reality.rosetta.domain

import org.reality.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.reality.schema.address.Address

import derevo.cats.eqv
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(eqv, customizableDecoder, customizableEncoder)
case class AccountIdentifier(
  address: Address,
  subAccount: Option[SubAccountIdentifier]
)

@derive(eqv, customizableDecoder, customizableEncoder)
case class SubAccountIdentifier(
  address: Address
)
