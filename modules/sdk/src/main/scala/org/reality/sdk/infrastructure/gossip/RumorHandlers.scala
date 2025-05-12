package org.reality.sdk.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.healthcheck.LocalHealthcheck
import org.reality.sdk.infrastructure.cluster.rumor.handler.nodeStateHandler
import org.reality.sdk.infrastructure.healthcheck.ping.PingHealthCheckConsensus
import org.reality.sdk.infrastructure.healthcheck.ping.handler.pingProposalHandler

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def make[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    pingHealthCheck: PingHealthCheckConsensus[F],
    localHealthcheck: LocalHealthcheck[F]
  ): RumorHandlers[F] =
    new RumorHandlers[F](clusterStorage, pingHealthCheck, localHealthcheck) {}
}

sealed abstract class RumorHandlers[F[_]: Async] private (
  clusterStorage: ClusterStorage[F],
  pingHealthCheck: PingHealthCheckConsensus[F],
  localHealthcheck: LocalHealthcheck[F]
) {
  private val nodeState = nodeStateHandler(clusterStorage, localHealthcheck)
  private val pingProposal = pingProposalHandler(pingHealthCheck)

  private val debug: RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    val strHandler = RumorHandler.fromCommonRumorConsumer[F, String] { rumor =>
      logger.info(s"String rumor received ${rumor.content}")
    }

    val optIntHandler = RumorHandler.fromPeerRumorConsumer[F, Option[Int]]() { rumor =>
      rumor.content match {
        case Some(i) if i > 0 => logger.info(s"Int rumor received ${i.show}, origin ${rumor.origin.show}")
        case o =>
          MonadThrow[F].raiseError(new RuntimeException(s"Int rumor error ${o.show}, origin ${rumor.origin.show}"))
      }
    }

    strHandler <+> optIntHandler
  }

  val handlers: RumorHandler[F] = nodeState <+> pingProposal <+> debug
}
