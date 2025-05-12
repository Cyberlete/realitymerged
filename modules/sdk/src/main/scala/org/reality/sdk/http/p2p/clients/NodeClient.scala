package org.reality.sdk.http.p2p.clients

import cats.effect.Async

import org.reality.schema.cluster.SessionToken
import org.reality.schema.node.NodeState
import org.reality.sdk.domain.cluster.services.Session
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.SecurityProvider

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait NodeClient[F[_]] {
  def getState: PeerResponse[F, NodeState]
  def health: PeerResponse[F, Boolean]
  def getSession: PeerResponse[F, Option[SessionToken]]
}

object NodeClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): NodeClient[F] =
    new NodeClient[F] with Http4sClientDsl[F] {

      def getState: PeerResponse[F, NodeState] =
        PeerResponse[F, NodeState]("node/state")(client, session)

      def health: PeerResponse[F, Boolean] =
        PeerResponse.successful("node/health")(client, session)

      def getSession: PeerResponse[F, Option[SessionToken]] =
        PeerResponse[F, Option[SessionToken]]("node/session")(client, session)
    }
}
