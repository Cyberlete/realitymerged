package org.reality.combined.node

import cats.effect.IO

import scala.concurrent.duration.DurationInt

import org.http4s.client.Client
import org.http4s.{Method, Request}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PeerManager(
  client: Client[IO],
  l0PeerInfo: PeerInfo,
  l1PeerInfo: PeerInfo
) {
  import HttpUtils._

  private val logger = Slf4jLogger.getLogger[IO]

  def getPeerInfo(nodeType: NodeType): PeerInfo = nodeType match {
    case NodeType.L0 => l0PeerInfo
    case NodeType.L1 => l1PeerInfo
  }

  def getPeerHealth(nodeType: NodeType): IO[Boolean] =
    for {
      connectionInfo <- IO.pure(getPeerInfo(nodeType))
      uri = buildUri(connectionInfo.host, connectionInfo.publicPort, "node/health")
      result <- client.successful(Request[IO](Method.GET, uri))
    } yield result

  def getPeerHealthWithRetry(nodeType: NodeType, maxRetries: Int = 3): IO[Boolean] = {
    def attemptHealth(retriesLeft: Int): IO[Boolean] =
      getPeerHealth(nodeType).attempt.flatMap {
        case Right(true) =>
          logger.info(s"${nodeType} - Health check attempt: Success") >>
            IO.pure(true)
        case _ if retriesLeft > 0 =>
          logger.info(s"${nodeType} - Health check attempt: Failed, retries left: ${retriesLeft - 1}") >>
            IO.sleep(2.seconds) >> attemptHealth(retriesLeft - 1)
        case _ =>
          logger.info(s"${nodeType} - Health check attempt: Failed, no retries left") >>
            IO.pure(false)
      }
    logger.info(s"${nodeType} - Starting health checks - Max retries: $maxRetries") >>
      attemptHealth(maxRetries)
  }
}

object PeerManager {
  def apply(
    client: Client[IO],
    l0PeerInfo: PeerInfo,
    l1PeerInfo: PeerInfo
  ): PeerManager = new PeerManager(client, l0PeerInfo, l1PeerInfo)
}
