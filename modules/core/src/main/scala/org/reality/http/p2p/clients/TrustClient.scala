package org.reality.http.p2p.clients

import cats.effect.Async

import org.reality.ext.codecs.BinaryCodec._
import org.reality.schema.trust.PublicTrust
import org.reality.sdk.domain.cluster.services.Session
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.SecurityProvider

import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait TrustClient[F[_]] {
  // Replace with publicTrust
  def getPublicTrust: PeerResponse[F, PublicTrust]
}

object TrustClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): TrustClient[F] =
    new TrustClient[F] with Http4sClientDsl[F] {

      def getPublicTrust: PeerResponse[F, PublicTrust] =
        PeerResponse[F, PublicTrust]("trust")(client, session)
    }
}
