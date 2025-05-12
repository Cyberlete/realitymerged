package org.reality.dag.l1.modules

import cats.effect.kernel.Async

import org.reality.dag.block.processing.BlockAcceptanceManager
import org.reality.dag.l1.domain.block.BlockService
import org.reality.dag.l1.domain.transaction.TransactionService
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.modules.Services
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.domain.cluster.services.{Cluster, Session}
import org.reality.sdk.domain.collateral.Collateral
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.healthcheck.LocalHealthcheck
import org.reality.sdk.domain.snapshot.services.L0Service
import org.reality.sdk.infrastructure.Collateral
import org.reality.security.SecurityProvider

object L1Services {

  def make[F[_]: Async: SecurityProvider](
    sdk: SDK[F],
    method: CliMethod,
    p2pClient: L1P2PClient[F],

    //    sdkServices: SdkServices[F],
//                                           queues: L1Queues[F],
    storages: L1Storages[F],
    validators: Validators[F]

    //    client: Client[F],
    //    session: Session[F],
    //    seedlist: Option[Set[PeerId]],
    //    selfId: PeerId,
    //    keyPair: KeyPair,
  ): L1Services[F] =
//    val validators = Validators.make[F](sdk, storages)
//    val l1P2PClient = L1P2PClient.make(sdk)

    new L1Services[F](
      localHealthcheck = sdk.sdkServices.localHealthcheck,
      block = BlockService.make[F](
        BlockAcceptanceManager.make[F](validators.block),
        storages.address,
        storages.block,
        storages.transaction,
        method.appConfig.collateral.amount
      ),
      cluster = sdk.sdkServices.cluster,
      gossip = sdk.sdkServices.gossip,
      l0 = L0Service
        .make[F](p2pClient.l0GlobalSnapshotClient, storages.l0Cluster, storages.lastGlobalSnapshotStorage, None),
      session = sdk.sdkServices.session,
      transaction = TransactionService.make[F](storages.transaction, validators.transactionContextual),
      collateral = Collateral.make[F](method.appConfig.collateral, storages.lastGlobalSnapshotStorage)
    ) {}

}

abstract class L1Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val block: BlockService[F],
  val cluster: Cluster[F],
  val gossip: Gossip[F],
  val l0: L0Service[F],
  val session: Session[F],
  val transaction: TransactionService[F],
  val collateral: Collateral[F]
) extends Services[F]
