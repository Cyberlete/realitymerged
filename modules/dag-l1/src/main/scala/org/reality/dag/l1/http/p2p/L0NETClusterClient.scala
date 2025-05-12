package org.reality.dag.l1.http.p2p

import org.reality.dag.domain.block.NETBlock
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.signature.Signed

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0NETClusterClient[F[_]] {
  def sendL1Output(output: Signed[NETBlock]): PeerResponse[F, Boolean]
}

object L0NETClusterClient {

  def make[F[_]](client: Client[F]): L0NETClusterClient[F] =
    new L0NETClusterClient[F] {

      def sendL1Output(output: Signed[NETBlock]): PeerResponse[F, Boolean] =
        PeerResponse("net/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
