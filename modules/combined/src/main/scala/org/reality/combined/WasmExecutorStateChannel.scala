package org.reality.combined

import java.security.KeyPair

import cats.effect.std.{Random, Semaphore}
import cats.effect.{Async, IO, Resource}
import cats.implicits._

import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.WasmOutputWrapper
import org.reality.dag.l1.domain.consensus.block.StateChannelCell
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.modules.{L1HttpApi, L1Programs, L1Queues, L1Services, L1Storages, Validators}
import org.reality.dag.l1.{ExecutionRecord, L1, MkStateChannel, StateChannel, WasmExecutionParams}
import org.reality.modules.{AdditionalRoutes, HttpApi}
import org.reality.schema.peer.PeerId
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.types.AppConfig
import org.reality.security.SecurityProvider

import fs2.Stream
import io.circe.{Decoder, Json}
import io.github.kawamuray.wasmtime.Val

private class WasmExecutorStateChannel[F[_]: Async: SecurityProvider: Random](
  override val appConfig: AppConfig,
  override val blockAcceptanceS: Semaphore[F],
  override val blockCreationS: Semaphore[F],
  override val blockStoringS: Semaphore[F],
  override val keyPair: KeyPair,
  p2pClient: L1P2PClient[F],
  override val programs: L1Programs[F],
  override val queues: L1Queues[F],
  override val selfId: PeerId,
  override val services: L1Services[F],
  override val storages: L1Storages[F],
  override val validators: Validators[F],
  override val cellObj: StateChannelCell,
  override val wasmPrograms: List[WasmExecutionParams[_, _]]
) extends L1[F](
      appConfig = appConfig,
      blockAcceptanceS = blockAcceptanceS,
      blockCreationS = blockCreationS,
      blockStoringS = blockStoringS,
      keyPair = keyPair,
      p2pClient = p2pClient,
      programs = programs,
      queues = queues,
      selfId = selfId,
      services = services,
      storages = storages,
      validators = validators,
      cellObj = cellObj,
      wasmPrograms
    ) {

  private val wasmExecutor = new WasmExecutor[F]

  private def loadWasmBinary(wasmPath: String): F[Array[Byte]] =
    Async[F].delay {
      java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(wasmPath))
    }

  private def wasmOutputStream: Stream[F, WasmOutputWrapper] = {
    val wasmProgramsMap: Map[String, WasmExecutionParams[_, _]] = wasmPrograms.map(p => p.functionName -> p).toMap

    val wasmBinaryStream: Stream[F, Array[Byte]] = Stream.eval(loadWasmBinary(wasmPrograms.head.wasmPath))

    wasmBinaryStream.flatMap { wasmBinary =>
      Stream.resource(wasmExecutor.setup(wasmBinary)).flatMap {
        case (store, instance) =>
          Stream.fromQueueUnterminated(queues.wasmExecutionQueue).evalMap { request =>
            wasmProgramsMap.get(request.data.functionName) match {
              case Some(wasmProgram) =>
                val parsedParamsF: F[Any] = parseParams(request.data.params, wasmProgram)

                parsedParamsF.flatMap { parsedParams =>
                  wasmExecutor
                    .executeFunction(
                      store,
                      instance,
                      wasmProgram.functionName,
                      parsedParams,
                      wasmBinary // Add wasmBinary parameter here
                    )(
                      wasmProgram.paramsConverter.asInstanceOf[Any => Array[Val]],
                      wasmProgram.resultConverter
                    )
                    .map {
                      case Right((result, _)) => // Updated to handle tuple with proof path
                        val serializeToJson = wasmProgram.serializeToJson.asInstanceOf[Any => Json]
                        WasmOutputWrapper(ExecutionRecord(request, serializeToJson(result)))
                      case Left(error: WasmError) => // Added type annotation
                        val errorJson = Json.obj("error" -> Json.fromString(error.message))
                        WasmOutputWrapper(ExecutionRecord(request, errorJson))
                    }
                }

              case None =>
                val errorJson = Json.obj("error" -> Json.fromString(s"Function '${request.data.functionName}' not found"))
                Async[F].pure(WasmOutputWrapper(ExecutionRecord(request, errorJson)))
            }
          }
      }
    }
  }

  private def parseParams(paramsJson: Json, wasmProgram: WasmExecutionParams[_, _]): F[Any] =
    wasmProgram.paramsDecoder.asInstanceOf[Decoder[Any]].decodeJson(paramsJson) match {
      case Right(params) => Async[F].pure(params)
      case Left(error)   => Async[F].raiseError(new Exception(s"Parameter parsing error: ${error.getMessage}"))
    }

  override val runtime: Stream[F, Unit] =
    blockConsensusInputs
      .merge(wasmOutputStream)
      .through(runConsensus)
      .through(gossipBlock)
      .through(sendBlockToL0)
      .merge(peerBlocks)
      .through(storeBlock)
      .merge(blockAcceptance)
      .merge(globalSnapshotProcessing)
      .merge(l0PeerDiscovery)
}

object WasmExecutorStateChannel extends MkStateChannel {
  val cellObj: StateChannelCell = WasmExecutorCellObj
  def make[F[_]: Async: SecurityProvider: Random](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2pClient: L1P2PClient[F],
    programs: L1Programs[F],
    queues: L1Queues[F],
    selfId: PeerId,
    services: L1Services[F],
    storages: L1Storages[F],
    validators: Validators[F],
    mkCell: StateChannelCell,
    wasmPrograms: List[WasmExecutionParams[_, _]]
  ): F[StateChannel[F, _, _]] =
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
        wasmPrograms
      )
  override def mkApi[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, (L1HttpApi[IO], Validators[IO])] = { (method: A, sdk: SDK[IO]) =>
    implicit val securityProvider: SecurityProvider[IO] = sdk.securityProvider

    val coCellRoutes: List[HttpApi[IO] => AdditionalRoutes[IO]] =
      List((api: HttpApi[IO]) => new WasmExecutorRoutes[IO](api, sdk.sdkValidators.transactionValidator))

    L1HttpApi.mkResourcesHack(method, sdk, coCellRoutes)
  }
}
