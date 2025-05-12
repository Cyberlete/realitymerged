package org.reality.modules

import cats.effect.Async

import org.reality.domain.snapshot.programs.Download
import org.reality.infrastructure.snapshot.programs.RollbackLoader
import org.reality.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.reality.sdk.modules.SdkPrograms

object L0Programs {

  def make[F[_]: Async](
    sdkPrograms: SdkPrograms[F],
//    sdk: SDK[F],
    storages: L0Storages[F],
    services: L0Services[F]
  ): L0Programs[F] = {
    val download = Download
      .make(
        storages.node,
        services.consensus
      )

    val rollbackLoader = RollbackLoader.make(storages.globalSnapshotLocalFileSystem, services.contextFunctions)

    new L0Programs[F](sdkPrograms.peerDiscovery, sdkPrograms.joining, download, rollbackLoader) {}
  }
}

abstract class L0Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F],
  val rollbackLoader: RollbackLoader[F]
) extends Programs[F]

abstract class Programs[F[_]]
