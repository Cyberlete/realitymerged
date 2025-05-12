package org.reality.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.reality.infrastructure.snapshot._
import org.reality.schema.node.NodeState
import org.reality.sdk.domain.node.NodeStorage

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: GlobalSnapshotConsensus[F]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: GlobalSnapshotConsensus[F]
) {

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      consensus.manager.startObserving
}
