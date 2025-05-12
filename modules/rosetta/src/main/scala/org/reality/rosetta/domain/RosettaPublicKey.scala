package org.reality.rosetta.domain

import org.reality.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.reality.security.hex.Hex

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import enumeratum.values._

@derive(customizableDecoder, customizableEncoder)
case class RosettaPublicKey(
  hexBytes: Hex,
  curveType: CurveType
)

sealed abstract class CurveType(val value: String) extends StringEnumEntry
object CurveType extends StringEnum[CurveType] with StringCirceEnum[CurveType] {
  val values = findValues

  case object SECP256K1 extends CurveType(value = "secp256k1")
}
