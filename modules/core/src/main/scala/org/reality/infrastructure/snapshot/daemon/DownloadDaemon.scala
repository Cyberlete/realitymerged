package org.reality.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.eq._
import cats.syntax.functor._

import org.reality.domain.snapshot.programs.Download
import org.reality.schema.node.NodeState
import org.reality.sdk.domain.Daemon
import org.reality.sdk.domain.node.NodeStorage

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async](nodeStorage: NodeStorage[F], download: Download[F])(implicit S: Supervisor[F]) = new DownloadDaemon[F] {
    def start: F[Unit] = S.supervise(watchForDownload).void

    private def watchForDownload: F[Unit] =
      nodeStorage.nodeStates
        .filter(_ === NodeState.WaitingForDownload)
        .evalTap { _ =>
          download.download()
        }
        .compile
        .drain
  }
}
