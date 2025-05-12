package org.reality.dag.l1

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder, Json}

case class ExecutionRecord(
  request: WasmExecutionRequest,
  result: Json
)

object ExecutionRecord {
  implicit val encoder: Encoder[ExecutionRecord] = deriveEncoder
  implicit val decoder: Decoder[ExecutionRecord] = deriveDecoder
}
