package org.reality.sdk.http.p2p.clients

import cats.effect.Async

import org.reality.schema.peer.PeerInfo
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.SecurityProvider

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0ClusterClient[F[_]] {
  def getPeers: PeerResponse[F, Set[PeerInfo]]
}

object L0ClusterClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F]): L0ClusterClient[F] =
    new L0ClusterClient[F] {
      private val logger = Slf4jLogger.getLogger[F]

      def getPeers: PeerResponse[F, Set[PeerInfo]] =
        PeerResponse[F, Set[PeerInfo]]("cluster/info")(client)
    }
}
