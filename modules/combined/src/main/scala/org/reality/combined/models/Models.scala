package org.reality.combined.models

import java.nio.file.Path
import java.security.KeyPair
import java.time.Instant

import org.reality.combined.WasmError

import io.circe.generic.semiauto._
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.github.kawamuray.wasmtime.{Instance, Store, Val}

// MongoDB Date Models
case class MongoDate($date: String)

object MongoDate {
  implicit val encoder: Encoder[MongoDate] = deriveEncoder
  implicit val decoder: Decoder[MongoDate] = deriveDecoder

  implicit val instantEncoder: Encoder[Instant] = Encoder.instance { instant =>
    Json.obj("$date" -> Json.fromString(instant.toString))
  }

  implicit val instantDecoder: Decoder[Instant] = Decoder.instance { cursor =>
    cursor.downField("$date").as[String].map(Instant.parse)
  }
}

// Movement Tracking Models
case class ScreenResolution(width: Int, height: Int)
object ScreenResolution {
  implicit val encoder: Encoder.AsObject[ScreenResolution] = deriveEncoder
  implicit val decoder: Decoder[ScreenResolution] = deriveDecoder
}

case class Metadata(
  playerId: String,
  deviceType: String,
  gameType: String,
  screenResolution: ScreenResolution,
  browserInfo: String,
  sessionStartTime: MongoDate,
  sessionEndTime: MongoDate,
  movementPattern: String
)
object Metadata {
  implicit val encoder: Encoder.AsObject[Metadata] = deriveEncoder
  implicit val decoder: Decoder[Metadata] = deriveDecoder
}

case class MouseEvent(
  x_position: Int,
  y_position: Int,
  timestamp: MongoDate
)
object MouseEvent {
  implicit val encoder: Encoder.AsObject[MouseEvent] = deriveEncoder
  implicit val decoder: Decoder[MouseEvent] = deriveDecoder
}

case class Movement(event: MouseEvent)
object Movement {
  implicit val encoder: Encoder.AsObject[Movement] = deriveEncoder
  implicit val decoder: Decoder[Movement] = deriveDecoder
}

case class MouseMovement(
  sessionId: String,
  status: String,
  movements: List[Movement],
  metadata: Metadata
)
object MouseMovement {
  implicit val encoder: Encoder.AsObject[MouseMovement] = deriveEncoder
  implicit val decoder: Decoder[MouseMovement] = deriveDecoder
}

// STARK Models
case class StarkProof(
  trace: Array[BigInt],
  traceMerkleRoot: Array[Byte],
  ldeTrace: Array[BigInt],
  ldeMerkleRoot: Array[Byte],
  friLayers: Seq[Array[BigInt]],
  friMerkleRoots: Seq[Array[Byte]],
  finalPolynomial: Array[BigInt],
  zeroKnowledgeMask: Array[BigInt],
  publicInputs: List[(String, BigInt)],
  proofRandomness: BigInt
)

object StarkProof {
  implicit val encoder: Encoder[StarkProof] = deriveEncoder
  implicit val decoder: Decoder[StarkProof] = deriveDecoder
}

case class StarkConfig(
  fieldPrime: BigInt,
  traceLength: Int,
  ldeExpansionFactor: Int,
  friFoldingFactor: Int,
  friLayers: Int,
  friQueries: Int
)

object StarkConfig {
  implicit val encoder: Encoder[StarkConfig] = deriveEncoder
  implicit val decoder: Decoder[StarkConfig] = deriveDecoder
}

case class StarkMetadata(
  name: String,
  timestamp: Long,
  traceRoot: Array[Byte],
  ldeRoot: Array[Byte],
  publicInputs: List[(String, BigInt)],
  friRoots: Seq[Array[Byte]],
  randomness: BigInt
)

object StarkMetadata {
  implicit val encoder: Encoder[StarkMetadata] = deriveEncoder
  implicit val decoder: Decoder[StarkMetadata] = deriveDecoder
}

// Key and Transaction Models
case class RealityKeyPair(
  publicKey: String,
  privateKey: String,
  keyPair: KeyPair
)

object RealityKeyPair {
  implicit val keyPairEncoder: Encoder[KeyPair] = new Encoder[KeyPair] {
    final def apply(keyPair: KeyPair): Json = Json.obj(
      "algorithm" -> Json.fromString(keyPair.getPrivate.getAlgorithm)
    )
  }

  implicit val keyPairDecoder: Decoder[KeyPair] = new Decoder[KeyPair] {
    final def apply(c: HCursor): Decoder.Result[KeyPair] =
      Left(DecodingFailure("KeyPair deserialization is not supported", c.history))
  }

  implicit val encoder: Encoder.AsObject[RealityKeyPair] = deriveEncoder
  implicit val decoder: Decoder[RealityKeyPair] = deriveDecoder
}

case class WasmRequest(
  functionName: String,
  params: String,
  privateKey: String,
  address: String,
  fee: Int
)

object WasmRequest {
  implicit val encoder: Encoder.AsObject[WasmRequest] = deriveEncoder
  implicit val decoder: Decoder[WasmRequest] = deriveDecoder
}

case class TransactionResponse(
  status: String,
  txHash: String,
  timestamp: Long,
  dataHash: String
)

object TransactionResponse {
  implicit val encoder: Encoder.AsObject[TransactionResponse] = deriveEncoder
  implicit val decoder: Decoder[TransactionResponse] = deriveDecoder
}

// WASM Executor Interface
trait WasmExecutor[F[_]] {
  def executeFunction[T, R](
    store: Store[Void],
    instance: Instance,
    name: String,
    params: T,
    wasmBinary: Array[Byte]
  )(paramsConverter: T => Array[Val], resultConverter: Array[Val] => R): F[Either[WasmError, (R, Option[Path])]]
}
