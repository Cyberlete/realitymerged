
import java.nio.file.{Files, Path, Paths}
import java.nio.{ByteBuffer, ByteOrder}
import cats.effect.IO
import cats.implicits.toTraverseOps
import org.reality.combined._


//import java.security.KeyPair

import io.circe.generic.auto._
import org.reality.combined.examples.CombinedL0

//import cats.effect.Async
//import cats.effect.std.{Random, Semaphore}
//import cats.implicits._

//import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.WasmOutputWrapper
//import org.reality.dag.l1.domain.consensus.block.StateChannelCell
//import org.reality.dag.l1.http.p2p.L1P2PClient
//import org.reality.dag.l1.modules.{L1Programs,   L1Queues, L1Services, L1Storages, Validators}
//import org.reality.dag.l1.{WasmExecutionParams}
//import org.reality.schema.peer.PeerId
//import org.reality.sdk.config.types.AppConfig
//import org.reality.security.SecurityProvider

//import fs2.Stream
import io.circe.{Decoder, Json}
import io.github.kawamuray.wasmtime.Val

//class WasmExecutorStateChannel[F[_]: Async: SecurityProvider: Random] (
//                                                                       override val appConfig: AppConfig,
//                                                                       override val blockAcceptanceS: Semaphore[F],
//                                                                       override val blockCreationS: Semaphore[F],
//                                                                       override val blockStoringS: Semaphore[F],
//                                                                       override val keyPair: KeyPair,
//                                                                       p2pClient: L1P2PClient[F],
//                                                                       override val programs: L1Programs[F],
//                                                                       override val queues: L1Queues[F],
//                                                                       override val selfId: PeerId,
//                                                                       override val services: L1Services[F],
//                                                                       override val storages: L1Storages[F],
//                                                                       override val validators: Validators[F],
//                                                                       override val cellObj: StateChannelCell,
//                                                                       val wasmPrograms: List[WasmExecutionParams[_, _]]
//                                                                     ) extends L1[F](
//  appConfig = appConfig,
//  blockAcceptanceS = blockAcceptanceS,
//  blockCreationS = blockCreationS,
//  blockStoringS = blockStoringS,
//  keyPair = keyPair,
//  p2pClient = p2pClient,
//  programs = programs,
//  queues = queues,
//  selfId = selfId,
//  services = services,
//  storages = storages,
//  validators = validators,
//  cellObj = cellObj
//) {
//
//  private val wasmExecutor = new WasmExecutor[F]
//
//  private def loadWasmBinary(wasmPath: String): F[Array[Byte]] =
//    Async[F].delay {
//      java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(wasmPath))
//    }
//
//  private def wasmOutputStream: Stream[F, WasmOutputWrapper] = {
//    val wasmProgramsMap: Map[String, WasmExecutionParams[_, _]] = wasmPrograms.map(p => p.functionName -> p).toMap
//
//    val wasmBinaryStream: Stream[F, Array[Byte]] = Stream.eval(loadWasmBinary(wasmPrograms.head.wasmPath))
//
//    wasmBinaryStream.flatMap { wasmBinary =>
//      Stream.resource(wasmExecutor.setup(wasmBinary)).flatMap {
//        case (store, instance) =>
//          Stream.fromQueueUnterminated(queues.wasmExecutionQueue).evalMap { request =>
//            wasmProgramsMap.get(request.data.functionName) match {
//              case Some(wasmProgram) =>
//                val parsedParamsF: F[Any] = parseParams(request.data.params, wasmProgram)
//
//                parsedParamsF.flatMap { parsedParams =>
//                  wasmExecutor
//                    .executeFunction(
//                      store,
//                      instance,
//                      wasmProgram.functionName,
//                      parsedParams,
//                      wasmBinary // Add wasmBinary parameter here
//                    )(
//                      wasmProgram.paramsConverter.asInstanceOf[Any => Array[Val]],
//                      wasmProgram.resultConverter
//                    )
//                    .map {
//                      case Right((result, _)) => // Updated to handle tuple with proof path
//                        val serializeToJson = wasmProgram.serializeToJson.asInstanceOf[Any => Json]
//                        WasmOutputWrapper(ExecutionRecord(request, serializeToJson(result)))
//                      case Left(error: WasmError) => // Added type annotation
//                        val errorJson = Json.obj("error" -> Json.fromString(error.message))
//                        WasmOutputWrapper(ExecutionRecord(request, errorJson))
//
//                    }
//                }
//
//              case None =>
//                val errorJson = Json.obj("error" -> Json.fromString(s"Function '${request.data.functionName}' not found"))
//                Async[F].pure(WasmOutputWrapper(ExecutionRecord(request, errorJson)))
//            }
//          }
//      }
//    }
//  }
//
//  private def parseParams(paramsJson: Json, wasmProgram: WasmExecutionParams[_, _]): F[Any] =
//    wasmProgram.paramsDecoder.asInstanceOf[Decoder[Any]].decodeJson(paramsJson) match {
//      case Right(params) => Async[F].pure(params)
//      case Left(error)   => Async[F].raiseError(new Exception(s"Parameter parsing error: ${error.getMessage}"))
//    }
//
//  override val runtime: Stream[F, Unit] =
//    blockConsensusInputs
//      .merge(wasmOutputStream)
//      .through(runConsensus)
//      .through(gossipBlock)
//      .through(sendBlockToL0)
//      .merge(peerBlocks)
//      .through(storeBlock)
//      .merge(blockAcceptance)
//      .merge(globalSnapshotProcessing)
//      .merge(l0PeerDiscovery)
//}


trait ZKWasmMovementAnalysis {
  def generateProof(
                     name: String,
                     publicInputs: List[(String, Any)],
                     privateInputs: List[(String, Any)]
                   ): IO[Either[ZKProofError, Path]]
}


import cats.effect.{Async, IO}

import org.reality.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext}
import org.reality.dag.l1.modules.L1HttpApi
import org.reality.dag.l1.{MkStateChannel, WasmExecutionParams}
import org.reality.kernel.{Cell, CellError, StackF, Ω}
import org.reality.sdk.app.SDK
import org.reality.security.SecurityProvider

case class WasmExecutorCoCell[T, R](wasmProgram: WasmExecutionParams[T, R]) extends CoCell {
  val stateChannel: MkStateChannel = WasmExecutorStateChannel
  def mkCell[F[_]: Async: SecurityProvider: cats.effect.std.Random](
                                                                     ctx: BlockConsensusContext[F]
                                                                   ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell(ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = WasmExecutorStateChannel.mkCell[F](ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
  }
  override def setup(args: List[String]): IO[CoCell.Context[CoCell]] =
    setupCombined[org.reality.dag.l1.cli.Run](
      stateChannel :: Nil,
      this,
      argsToStartUp(args).l1method,
      org.reality.dag.l1.cli.method.opts,
      L1HttpApi.mkResources(_: org.reality.dag.l1.cli.Run, _: SDK[IO])
      //      ,
      //      List(wasmProgram)
    )
}

object MovementAnalysisExample extends Portal with ZKWasmMovementAnalysis {
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
