package org.reality.dag.l1

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder, Json}

case class WasmExecutionRequestData(functionName: String, params: Json)

object WasmExecutionRequestData {
  implicit val encoder: Encoder[WasmExecutionRequestData] = deriveEncoder
  implicit val decoder: Decoder[WasmExecutionRequestData] = deriveDecoder
}
