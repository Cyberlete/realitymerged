package org.reality.dag.l1

import org.reality.schema.transaction.RecordDataTransaction
import org.reality.security.signature.Signed

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, Json}

case class DataHashRequest(transaction: Signed[RecordDataTransaction], data: Signed[Json])

object DataHashRequest {
  implicit val encoder: Encoder[DataHashRequest] = deriveEncoder
  implicit val decoder: Decoder[DataHashRequest] = deriveDecoder
}
