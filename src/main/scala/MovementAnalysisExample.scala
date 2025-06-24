import java.nio.file.{Files, Path, Paths}
import java.nio.{ByteBuffer, ByteOrder}
import cats.effect.IO
import cats.implicits.toTraverseOps
import org.reality.combined._
import org.reality.combined.examples.ZKWasm
import org.reality.dag.l1.WasmExecutionParams


import io.circe.generic.auto._
import org.reality.combined.examples.CombinedL0

import io.circe.{Decoder, Json}
import io.github.kawamuray.wasmtime.Val


object MovementAnalysisExample extends Portal with ZKWasm {
  import CoCell._

  // Match the incoming JSON structure
  case class MouseEvent(
                         x_position: Float,
                         y_position: Float,
                         timestamp: Long,
                         left_click: Boolean,
                         right_click: Boolean,
                         button_3: Boolean,
                         button_4: Boolean,
                         button_5: Boolean
                       )

  case class MovementParams(events: List[MouseEvent])

  case class MovementAnalysis(
                               totalPoints: Int,
                               lineScore: Float,
                               speedScore: Float
                             )

  def convertButtonsToFlags(event: MouseEvent): Int = {
    var flags = 0
    if (event.left_click) flags |= 1
    if (event.right_click) flags |= 2
    if (event.button_3) flags |= 4
    if (event.button_4) flags |= 8
    if (event.button_5) flags |= 16
    flags
  }

  val wasmProgram: WasmExecutionParams[MovementParams, MovementAnalysis] = WasmExecutionParams(
    wasmPath = "/app/wasm/movement.wasm",
    functionName = "exported_analyze_points_wasm",
    paramsConverter = { params: MovementParams =>
      val eventCount = params.events.length
      val totalSize = eventCount * 32 // 32 bytes per event

      val buffer = ByteBuffer.allocateDirect(totalSize)
      buffer.order(ByteOrder.LITTLE_ENDIAN)

      params.events.foreach { event =>
        buffer.putFloat(event.x_position)
        buffer.putFloat(event.y_position)
        buffer.putLong(event.timestamp)
        buffer.putInt(convertButtonsToFlags(event))
        buffer.position(buffer.position() + 12) // Padding to 32 bytes
      }

      Array(Val.fromI32(0), Val.fromI32(eventCount))
    },
    resultConverter = { vals =>
      MovementAnalysis(
        totalPoints = vals(0).i32(),
        lineScore = vals(1).f32(),
        speedScore = vals(2).f32()
      )
    },
    serializeToJson = { result =>
      Json.obj(
        "total_points" -> Json.fromInt(result.totalPoints),
        "line_score" -> Json.fromFloatOrNull(result.lineScore),
        "speed_score" -> Json.fromFloatOrNull(result.speedScore)
      )
    },
    paramsDecoder = Decoder[MovementParams]
  )

  private val l0CoCell: CombinedL0 = CombinedL0()
  private val stateChannelCoCell: WasmExecutorCoCell[MovementParams, MovementAnalysis] =
    WasmExecutorCoCell(wasmProgram)

  val mergedCells: Seq[CoCell] = l0CoCell ++ stateChannelCoCell

  val cellProgram: List[String] => IO[Seq[Context[CoCell]]] = (args: List[String]) =>
    for {
      coCellsInContext <- mergedCells.map(_.setup(args)).sequence
    } yield coCellsInContext

  protected val wasmExecutor = new RealZKWasmExecutor[IO]

  def executeWasmWithProof(params: MovementParams): IO[Either[WasmError, (MovementAnalysis, Option[Path])]] =
    for {
      wasmBytes <- IO.delay(Files.readAllBytes(Paths.get(wasmProgram.wasmPath)))
      result <- wasmExecutor.setup(wasmBytes).use {
        case (store, instance) =>
          wasmExecutor.executeFunction(
            store,
            instance,
            wasmProgram.functionName,
            params,
            wasmBytes
          )(wasmProgram.paramsConverter, wasmProgram.resultConverter)
      }
    } yield result

  def analyzeWithProof(params: MovementParams): IO[Either[WasmError, (MovementAnalysis, Path)]] =
    executeWasmWithProof(params).map {
      case Right((analysis, Some(proofPath))) => Right((analysis, proofPath))
      case Right((_, None)) => Left(ProofGenerationErrorWasm("Proof was not generated")) // Changed to use ProofGenerationErrorWasm
      case Left(error)      => Left(error)
    }

  override def generateProof(
                              name: String,
                              publicInputs: List[(String, Any)],
                              privateInputs: List[(String, Any)]
                            ): IO[Either[ZKProofError, Path]] =
    wasmExecutor.generateProof(name, publicInputs, privateInputs)
}
