package org.reality.sdk.infrastructure.cluster.rumor

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.reality.schema.gossip.PeerRumor
import org.reality.schema.node.NodeState
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.healthcheck.LocalHealthcheck
import org.reality.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def nodeStateHandler[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    localHealthcheck: LocalHealthcheck[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, NodeState](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, state) =>
        clusterStorage.hasPeerId(origin).flatMap { hasPeer =>
          logger.info(s"Received state=${state.show} from id=${origin.show}. Peer is ${if (hasPeer) "" else "un"}known.")
        } >>
          clusterStorage.setPeerState(origin, state) >> {
            if (NodeState.absent.contains(state))
              localHealthcheck.cancel(origin) >>
                clusterStorage.removePeer(origin)
            else
              Applicative[F].unit
          }
    }
  }
}
