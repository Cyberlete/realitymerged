package org.reality.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.domain.snapshot.GlobalSnapshotStorage
import org.reality.infrastructure.snapshot.{GlobalSnapshotLocalFileSystemStorage, GlobalSnapshotStorage}
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.reality.sdk.domain.collateral.LatestBalances
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.gossip.RumorStorage
import org.reality.sdk.infrastructure.trust.storage.TrustStorage

object L0Storages {

  def make[F[_]: Async: Supervisor](
    sdk: SDK[F],
    snapshotConfig: CliMethod
  ): F[L0Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F]
      globalSnapshotLocalFileSystemStorage <- GlobalSnapshotLocalFileSystemStorage.make(
        snapshotConfig.snapshotConfig.globalSnapshotPath
      )
      globalSnapshotStorage <- GlobalSnapshotStorage
        .make[F](globalSnapshotLocalFileSystemStorage, snapshotConfig.snapshotConfig.inMemoryCapacity)
    } yield
      new L0Storages[F](
        cluster = sdk.sdkStorages.cluster,
        node = sdk.sdkStorages.node,
        session = sdk.sdkStorages.session,
        rumor = sdk.sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage,
        globalSnapshotLocalFileSystem = globalSnapshotLocalFileSystemStorage
      ) {}
}
abstract class L0Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: GlobalSnapshotStorage[F] with LatestBalances[F],
  val globalSnapshotLocalFileSystem: GlobalSnapshotLocalFileSystemStorage[F]
) extends Storages[F]

abstract class Storages[F[_]] //cluster, node could go here
