package org.reality.rosetta.domain.api.construction

import org.reality.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.reality.rosetta.domain.{AccountIdentifier, NetworkIdentifier, RosettaPublicKey}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionDerive {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    publicKey: RosettaPublicKey
  )

  @derive(customizableEncoder)
  case class Response(
    accountIdentifier: AccountIdentifier
  )
}
