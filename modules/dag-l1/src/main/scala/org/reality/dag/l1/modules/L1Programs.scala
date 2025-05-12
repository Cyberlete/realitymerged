package org.reality.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.reality.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.modules.Programs
import org.reality.sdk.app.SDK
import org.reality.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.reality.security.SecurityProvider

object L1Programs {

  def make[F[_]: Async: SecurityProvider: Random](
    sdk: SDK[F],
//    sdkPrograms: SdkPrograms[F],
    p2pClient: L1P2PClient[F],
    storages: L1Storages[F]
//    snapshotProcessorProgram: SnapshotProcessor[F]
  ): L1Programs[F] = {
    val l0PeerDiscovery = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
    val snapshotProcessor = SnapshotProcessor.make[F](
      storages.address,
      storages.block,
      storages.lastGlobalSnapshotStorage,
      storages.transaction,
      sdk.sdkServices.globalSnapshotContextFunctions
    )

    new L1Programs[F](sdk.sdkPrograms.peerDiscovery, l0PeerDiscovery, sdk.sdkPrograms.joining, snapshotProcessor) {}
  }
}

abstract class L1Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val l0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val snapshotProcessor: SnapshotProcessor[F]
) extends Programs[F]
