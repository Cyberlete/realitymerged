package org.reality.sdk.infrastructure.trust

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.reality.schema.gossip.PeerRumor
import org.reality.schema.trust.PublicTrust
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def trustHandler[F[_]: Async](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, PublicTrust](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, trust) =>
        logger.info(s"Received trust=${trust} from id=${origin.show}") >> {
          // Placeholder for updating trust scores
          Applicative[F].unit
        }
    }
  }
}
