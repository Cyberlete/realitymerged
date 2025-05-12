package org.reality.combined

import java.security.KeyPair

import cats.effect.std.{Random, Semaphore}
import cats.effect.{Async, IO, Resource}
import cats.implicits._

import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.DataHashWrapper
import org.reality.dag.l1.domain.consensus.block.StateChannelCell
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.modules.{L1HttpApi, L1Programs, L1Queues, L1Services, L1Storages, Validators}
import org.reality.dag.l1.{L1, MkStateChannel, StateChannel, WasmExecutionParams}
import org.reality.modules.{AdditionalRoutes, HttpApi}
import org.reality.schema.peer.PeerId
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.types.AppConfig
import org.reality.security.SecurityProvider

import fs2.Stream

private class DataHashStateChannel[F[_]: Async: SecurityProvider: cats.effect.std.Random](
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

  private def dataHashStream: Stream[F, DataHashWrapper] =
    Stream.fromQueueUnterminated(queues.dataQueue).evalMap { request =>
      Async[F].pure(DataHashWrapper(request))
    }

  override val runtime: Stream[F, Unit] =
    blockConsensusInputs
      .merge(dataHashStream)
      .through(runConsensus)
      .through(gossipBlock)
      .through(sendBlockToL0)
      .merge(peerBlocks)
      .through(storeBlock)
      .merge(blockAcceptance)
      .merge(globalSnapshotProcessing)
      .merge(l0PeerDiscovery)

}

object DataHashStateChannel extends MkStateChannel {
  val cellObj: StateChannelCell = DataHashCellObj

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
      new DataHashStateChannel[F](
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
      List((api: HttpApi[IO]) => new DataHashRoutes[IO](api, sdk.sdkValidators.transactionValidator))

    L1HttpApi.mkResourcesHack(method, sdk, coCellRoutes)
  }
}
