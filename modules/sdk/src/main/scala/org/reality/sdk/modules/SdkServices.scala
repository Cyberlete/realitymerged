package org.reality.sdk.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.block.processing.BlockAcceptanceManager
import org.reality.schema.generation.Generation
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.types.SdkConfig
import org.reality.sdk.domain.cluster.services.{Cluster, Session}
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.healthcheck.LocalHealthcheck
import org.reality.sdk.http.p2p.clients.NodeClient
import org.reality.sdk.infrastructure.cluster.services.Cluster
import org.reality.sdk.infrastructure.gossip.Gossip
import org.reality.sdk.infrastructure.healthcheck.LocalHealthcheck
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceFunctions,
  GlobalSnapshotContextFunctions,
  GlobalSnapshotStateChannelEventsProcessor
}
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: SecurityProvider: Metrics: Supervisor](
    cfg: SdkConfig,
    validators: SdkValidators[F],
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    seedlist: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash
  ): F[SdkServices[F]] = {

    val cluster = Cluster
      .make[F](
        cfg.leavingDelay,
        cfg.httpConfig,
        nodeId,
        keyPair,
        storages.cluster,
        storages.session,
        storages.node,
        seedlist,
        restartSignal,
        versionHash
      )

    for {
      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- Gossip.make[F](queues.rumor, nodeId, generation, keyPair)
      globalSnapshotAcceptanceFunctions = GlobalSnapshotAcceptanceFunctions.make(
        BlockAcceptanceManager.make[F](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator
          ),
        cfg.collateral.amount
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(globalSnapshotAcceptanceFunctions)
    } yield
      new SdkServices[F](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotAcceptanceFunctions = globalSnapshotAcceptanceFunctions,
        globalSnapshotContextFunctions = globalSnapshotContextFns
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotAcceptanceFunctions: GlobalSnapshotAcceptanceFunctions[F],
  val globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F]
)
