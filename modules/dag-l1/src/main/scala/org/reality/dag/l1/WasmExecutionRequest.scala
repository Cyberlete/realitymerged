package org.reality.dag.l1

import org.reality.schema.transaction.RecordDataTransaction
import org.reality.security.signature.Signed

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

case class WasmExecutionRequest(
  transaction: Signed[RecordDataTransaction],
  data: Signed[WasmExecutionRequestData]
)
object WasmExecutionRequest {
  implicit val encoder: Encoder[WasmExecutionRequest] = deriveEncoder
  implicit val decoder: Decoder[WasmExecutionRequest] = deriveDecoder
}
