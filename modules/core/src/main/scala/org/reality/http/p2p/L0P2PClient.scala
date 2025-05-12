package org.reality.http.p2p

import cats.effect.Async

import org.reality.http.p2p.clients._
import org.reality.sdk.app.SDK
import org.reality.sdk.http.p2p.clients.{ClusterClient, NodeClient, SignClient}
import org.reality.sdk.infrastructure.gossip.p2p.GossipClient
import org.reality.security.SecurityProvider

object L0P2PClient {

  def make[F[_]: Async: SecurityProvider](
    sdk: SDK[F]
//    sdkP2PClient: SdkP2PClient[F],
//    client: Client[F],
//    session: Session[F]
  ): L0P2PClient[F] =
    new L0P2PClient[F](
      sdk.sdkP2PClient.sign,
      sdk.sdkP2PClient.cluster,
      sdk.sdkP2PClient.gossip,
      sdk.sdkP2PClient.node,
      TrustClient.make[F](sdk.sdkResources.client, sdk.sdkServices.session),
      GlobalSnapshotClient.make[F](sdk.sdkResources.client, sdk.sdkServices.session)
    ) {}
}

sealed abstract class L0P2PClient[F[_]](
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val trust: TrustClient[F],
  val globalSnapshot: GlobalSnapshotClient[F]
) extends P2PClient[F]

trait P2PClient[F[_]]
