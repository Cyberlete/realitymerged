package org.reality.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.reality.dag.block.BlockValidator
import org.reality.dag.block.processing.BlockAcceptanceManager
import org.reality.domain.rewards.Rewards
import org.reality.domain.snapshot.GlobalSnapshotStorage
import org.reality.schema.balance.Amount
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.types.SnapshotConfig
import org.reality.sdk.domain.cluster.services.Session
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.consensus.Consensus
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider

import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: SecurityProvider: Metrics: Supervisor](
    trustStorage: TrustStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    blockValidator: BlockValidator[F],
    stateChannelValidator: StateChannelValidator[F],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] =
    Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
      GlobalSnapshotConsensusFunctions.make[F](
        globalSnapshotStorage,
        BlockAcceptanceManager.make[F](blockValidator),
        org.reality.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor.make[F](stateChannelValidator),
        collateral,
        rewards,
        environment
      ),
      gossip,
      selfId,
      keyPair,
      snapshotConfig.consensus,
      seedlist,
      clusterStorage,
      nodeStorage,
      client,
      session,
      trustStorage,
      _.blocks.map(_.block.value)
    )

}
