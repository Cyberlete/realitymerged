// Create file at: modules/combined/src/main/scala/org/reality/combined/node/types.scala
package org.reality.combined.node

import io.circe.{Decoder, Encoder}

case class AddressBalance(address: String, balance: BigInt)

object AddressBalance {
  implicit val encoder: Encoder[AddressBalance] = io.circe.generic.semiauto.deriveEncoder
  implicit val decoder: Decoder[AddressBalance] = io.circe.generic.semiauto.deriveDecoder
}
