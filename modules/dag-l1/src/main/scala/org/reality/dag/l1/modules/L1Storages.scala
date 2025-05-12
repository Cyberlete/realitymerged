package org.reality.dag.l1.modules

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.l1.domain.address.storage.AddressStorage
import org.reality.dag.l1.domain.block.BlockStorage
import org.reality.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.reality.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.reality.dag.l1.domain.transaction.TransactionStorage
import org.reality.dag.l1.infrastructure.address.storage.AddressStorage
import org.reality.modules.Storages
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.reality.sdk.domain.collateral.LatestBalances
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.reality.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.reality.sdk.infrastructure.gossip.RumorStorage

object L1Storages {

  def make[F[_]: Async: Random](
    sdk: SDK[F],
//    sdkStorages: SdkStorages[F],
    method: CliMethod
  ): F[L1Storages[F]] =
    for {
      blockStorage <- BlockStorage.make[F]
      consensusStorage <- ConsensusStorage.make[F]
      l0ClusterStorage <- L0ClusterStorage.make[F](method.l0Peer)
      lastGlobalSnapshotStorage <- LastGlobalSnapshotStorage.make[F]
      transactionStorage <- TransactionStorage.make[F]
      addressStorage <- AddressStorage.make[F]
    } yield
      new L1Storages[F](
        address = addressStorage,
        block = blockStorage,
        consensus = consensusStorage,
        cluster = sdk.sdkStorages.cluster,
        l0Cluster = l0ClusterStorage,
        lastGlobalSnapshotStorage = lastGlobalSnapshotStorage,
        node = sdk.sdkStorages.node,
        session = sdk.sdkStorages.session,
        rumor = sdk.sdkStorages.rumor,
        transaction = transactionStorage
      ) {}
}
abstract class L1Storages[F[_]] private (
  val address: AddressStorage[F],
  val block: BlockStorage[F],
  val consensus: ConsensusStorage[F],
  val cluster: ClusterStorage[F],
  val l0Cluster: L0ClusterStorage[F],
  val lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F] with LatestBalances[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val transaction: TransactionStorage[F]
) extends Storages[F]
