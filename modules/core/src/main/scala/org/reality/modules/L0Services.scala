package org.reality.modules

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.domain.cell.L0Cell
import org.reality.domain.net.NETService
import org.reality.domain.rewards.Rewards
import org.reality.domain.statechannel.StateChannelService
import org.reality.infrastructure.net.NETService
import org.reality.infrastructure.rewards._
import org.reality.infrastructure.snapshot._
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.domain.cluster.services.{Cluster, Session}
import org.reality.sdk.domain.collateral.Collateral
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.healthcheck.LocalHealthcheck
import org.reality.sdk.infrastructure.Collateral
import org.reality.sdk.infrastructure.consensus.Consensus
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotContextFunctions
import org.reality.security.SecurityProvider

object L0Services {
  // import sdk._
  def make[F[_]: Async: Random: SecurityProvider: Metrics: Supervisor](
    sdk: SDK[F],
    queues: L0Queues[F],
    storages: L0Storages[F],
    method: CliMethod
  ): F[L0Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          method.appConfig.rewards.rewardsPerEpoch,
          RegularDistributor.make
        )
        .pure[F]
      consensus <- GlobalSnapshotConsensus
        .make[F](
          storages.trust,
          sdk.sdkServices.gossip,
          sdk.nodeId,
          sdk.keyPair,
          sdk.seedlist,
          method.appConfig.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          sdk.sdkValidators.blockValidator,
          sdk.sdkValidators.stateChannelValidator,
          method.appConfig.snapshot,
          method.appConfig.environment,
          sdk.sdkResources.client,
          sdk.sdkServices.session,
          rewards
        )
      dagService = NETService.make[F](storages.globalSnapshot)
      collateralService = Collateral.make[F](method.appConfig.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), sdk.sdkValidators.stateChannelValidator)
    } yield
      new L0Services[F](
        localHealthcheck = sdk.sdkServices.localHealthcheck,
        cluster = sdk.sdkServices.cluster,
        session = sdk.sdkServices.session,
        gossip = sdk.sdkServices.gossip,
        consensus = consensus,
        dag = dagService,
        collateral = collateralService,
        rewards = rewards,
        stateChannel = stateChannelService,
        contextFunctions = sdk.sdkServices.globalSnapshotContextFunctions
      ) {}
}

abstract class L0Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext],
  val dag: NETService[F],
  val collateral: Collateral[F],
  val rewards: Rewards[F],
  val stateChannel: StateChannelService[F],
  val contextFunctions: GlobalSnapshotContextFunctions[F]
) extends Services[F]

abstract class Services[F[_]]
