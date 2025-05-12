package org.reality.dag.l1.http.p2p

import cats.effect.Async

import org.reality.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.reality.http.p2p.P2PClient
import org.reality.sdk.app.SDK
import org.reality.sdk.http.p2p.clients._
import org.reality.sdk.infrastructure.gossip.p2p.GossipClient
import org.reality.security.SecurityProvider

object L1P2PClient {

  def make[F[_]: Async: SecurityProvider](
    sdk: SDK[F] // sdkP2PClient, sdkResources.client)

    //    sdkP2PClient: SdkP2PClient[F],
    //    client: Client[F]
  ): L1P2PClient[F] =
    new L1P2PClient[F](
      sdk.sdkP2PClient.sign,
      sdk.sdkP2PClient.node,
      sdk.sdkP2PClient.cluster,
      L0ClusterClient.make(sdk.sdkResources.client),
      L0NETClusterClient.make(sdk.sdkResources.client),
      sdk.sdkP2PClient.gossip,
      BlockConsensusClient.make(sdk.sdkResources.client),
      L0GlobalSnapshotClient.make(sdk.sdkResources.client)
    ) {}
}

abstract class L1P2PClient[F[_]](
  val sign: SignClient[F],
  val node: NodeClient[F],
  val cluster: ClusterClient[F],
  val l0Cluster: L0ClusterClient[F],
  val l0DAGCluster: L0NETClusterClient[F],
  val gossip: GossipClient[F],
  val blockConsensus: BlockConsensusClient[F],
  val l0GlobalSnapshotClient: L0GlobalSnapshotClient[F]
) extends P2PClient[F]
