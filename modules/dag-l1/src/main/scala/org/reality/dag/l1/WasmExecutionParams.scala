package org.reality.dag.l1

import io.circe.{Decoder, Json}
import io.github.kawamuray.wasmtime.Val

case class WasmExecutionParams[T, R](
  wasmPath: String,
  functionName: String,
  paramsConverter: T => Array[Val],
  resultConverter: Array[Val] => R,
  serializeToJson: R => Json,
  paramsDecoder: Decoder[T]
)
