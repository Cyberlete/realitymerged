import MovementAnalysisExample.{MovementAnalysis, MovementParams}

import java.nio.{ByteBuffer, ByteOrder}
import java.security.KeyPair
import cats.effect.std.{Random, Semaphore}
import cats.effect.{Async, IO, Resource, Temporal}
import cats.implicits._
import org.reality.dag.l1.domain.consensus.block.StateChannelCell
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.modules.{L1HttpApi, L1Programs, L1Queues, L1Services, L1Storages, Validators}
import org.reality.dag.l1.{MkStateChannel, StateChannel, WasmExecutionParams}
import org.reality.modules.{AdditionalRoutes, HttpApi}
import org.reality.schema.peer.PeerId
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.types.AppConfig
import org.reality.security.SecurityProvider
import io.circe.{Decoder, Json}
import io.github.kawamuray.wasmtime.Val
import org.reality.combined.{WasmExecutorRoutes, WasmExecutorStateChannel}

object CyberleteWasmExecutorStateChannel extends MkStateChannel {
  val cellObj: StateChannelCell = CyberleteWasmExecutorCellObj
  //original L1 BlockConsensusCell
  // Maximum events per request to prevent memory exhaustion
  private val MaxEventsPerRequest = 10000

  val wasmProgram: WasmExecutionParams[MovementParams, MovementAnalysis] = WasmExecutionParams(
    wasmPath = "movement.wasm",
    functionName = "exported_analyze_points_wasm",
    paramsConverter = { case params: MovementParams =>
      val eventCount = params.events.length

      // Validate event count to prevent OOM
      require(eventCount > 0 && eventCount <= MaxEventsPerRequest,
        s"Event count must be between 1 and $MaxEventsPerRequest, got $eventCount")

      val totalSize = eventCount * 32 // 32 bytes per event

      // Allocate direct buffer for WASM memory
      val buffer = ByteBuffer.allocateDirect(totalSize)
      buffer.order(ByteOrder.LITTLE_ENDIAN)

      params.events.foreach { event =>
        buffer.putFloat(event.x_position)
        buffer.putFloat(event.y_position)
        buffer.putLong(event.timestamp)
        buffer.putInt(MovementAnalysisExample.convertButtonsToFlags(event))
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
  def make[F[_]: {Async, SecurityProvider, Random}](
                                                   appConfig: AppConfig,
                                                   keyPair: KeyPair,
                                                   p2pClient: L1P2PClient[F],
                                                   programs: L1Programs[F],
                                                   queues: L1Queues[F],
                                                   selfId: PeerId,
                                                   services: L1Services[F],
                                                   storages: L1Storages[F],
                                                   validators: Validators[F],
                                                   mkCell: StateChannelCell
                                                 ): F[StateChannel[F, ?, ?]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
    } yield
      new WasmExecutorStateChannel[F](
        appConfig,
        blockAcceptanceS,
        blockCreationS,
        blockStoringS,
        keyPair,
        p2pClient,
        programs,
        queues,
        selfId,
        services,
        storages,
        validators,
        mkCell,
        List(wasmProgram)
      )
  override def mkApi[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, (L1HttpApi[IO], Validators[IO])] = { (method: A, sdk: SDK[IO]) =>
    implicit val securityProvider: SecurityProvider[IO] = sdk.securityProvider

    val coCellRoutes: List[HttpApi[IO] => AdditionalRoutes[IO]] =
      List((api: HttpApi[IO]) => new WasmExecutorRoutes[IO](api, sdk.sdkValidators.transactionValidator))

    L1HttpApi.mkResourcesHack(method, sdk, coCellRoutes)
  }
}
