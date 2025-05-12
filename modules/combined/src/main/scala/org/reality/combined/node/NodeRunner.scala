package org.reality.combined.node

import cats.effect._
import cats.syntax.all._

import scala.concurrent.duration.DurationInt
import org.reality.schema.cluster.PeerToJoin
import org.reality.schema.peer.PeerId
import org.reality.security.hex.Hex
import com.comcast.ip4s.{Host, Port}
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.{Method, Request}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.reality.L0Helper
import org.reality.dag.l1.L1Helper

class NodeRunner(
  client: Client[IO],
  peerManager: PeerManager,
  l0Config: L0NodeConfig,
  l1Config: L1NodeConfig
) {
  import HttpUtils._

  private val logger = Slf4jLogger.getLogger[IO]

  private val l0NodeFiber: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]] =
    Ref.unsafe(None)

  private val l1NodeFiber: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]] =
    Ref.unsafe(None)

  def getConfig(nodeType: NodeType): BaseNodeConfig = nodeType match {
    case NodeType.L0 => l0Config
    case NodeType.L1 => l1Config
  }

  def isActive(nodeType: NodeType): IO[Boolean] =
    getNodeFiberRef(nodeType).get.map(_.isDefined)

  private def leaveCluster(nodeType: NodeType): IO[Boolean] = {
    val config = getConfig(nodeType)
    val uri = buildUri("127.0.0.1", config.cliPort, "cluster/leave")
    logger.info(s"${nodeType} - Leaving cluster") >>
      client.successful(Request[IO](Method.GET, uri)).flatTap { success =>
        logger.info(s"${nodeType} - Leave cluster result: ${if (success) "success" else "failed"}")
      }
  }

  def toggleNode(nodeType: NodeType): IO[ToggleNodeResult] = {
    val fiberRef = getNodeFiberRef(nodeType)

    for {
      start <- Clock[IO].monotonic
      _ <- logger.info(s"${nodeType} - Starting toggle operation")
      currentState <- fiberRef.get
      result <- currentState match {
        case Some(_) =>
          logger.info(s"${nodeType} - Stopping existing node") >>
            stopExistingNode(nodeType, fiberRef)
              .handleErrorWith(error =>
                logger.error(error)(s"${nodeType} - stopping a node failed") >>
                  IO.pure(ToggleNodeResult.Failed)
              )
        case None =>
          logger.info(s"${nodeType} - Starting new node") >>
            startNewNode(nodeType, fiberRef)
              .handleErrorWith(error =>
                logger.error(error)(s"${nodeType} - starting a node failed") >>
                  stopAndClearNode(fiberRef) >>
                  IO.pure(ToggleNodeResult.Failed)
              )
      }
      end <- Clock[IO].monotonic
      _ <- logger.info(
        s"${nodeType} - Operation completed - Duration: ${end.minus(start).toMillis}ms, Result: $result"
      )
    } yield result
  }

  private def getNodeFiberRef(nodeType: NodeType): Ref[IO, Option[Fiber[IO, Throwable, Nothing]]] =
    nodeType match {
      case NodeType.L0 => l0NodeFiber
      case NodeType.L1 => l1NodeFiber
    }

  private def stopNode(fiber: Option[Fiber[IO, Throwable, Nothing]]): IO[Unit] =
    fiber.traverse_(_.cancel)

  private def stopAndClearNode(fiberRef: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]]): IO[Unit] =
    fiberRef.get.flatMap(stopNode) >> fiberRef.set(None)

  private def setupNode(nodeType: NodeType): IO[Fiber[IO, Throwable, Nothing]] =
    nodeType match {
      case NodeType.L0 =>
        val config = l0Config
        val args = List(
          "run-validator",
          "--env",
          config.env,
          "--keyhex",
          config.keyHex,
          "--ip",
          config.ip,
          "--public-port",
          config.publicPort,
          "--p2p-port",
          config.p2pPort,
          "--cli-port",
          config.cliPort,
          "--collateral",
          config.collateral,
          "--snapshot-stored-path",
          config.snapshotStoredPath
        )
        L0Helper
          .setup(args)
          .flatMap {
            case (resource, _) =>
              resource.use(_ => IO.never)
          }
          .start

      case NodeType.L1 =>
        val config = l1Config
        val args = List(
          "run-validator",
          "--env",
          config.env,
          "--keyhex",
          config.keyHex,
          "--ip",
          config.ip,
          "--public-port",
          config.publicPort,
          "--p2p-port",
          config.p2pPort,
          "--cli-port",
          config.cliPort,
          "--l0-peer-id",
          config.l0PeerId,
          "--l0-peer-host",
          config.l0PeerHost,
          "--l0-peer-port",
          config.l0PeerPort,
          "--collateral",
          config.collateral,
          "--aci-db-path",
          config.aciDbHomePath
        )
        L1Helper
          .setup(args)
          .flatMap {
            case (resource, _) =>
              resource.use(_ => IO.never)
          }
          .start
    }

  private def startNewNode(
    nodeType: NodeType,
    fiberRef: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]]
  ): IO[ToggleNodeResult] =
    setupNode(nodeType).attempt.flatMap {
      case Left(error) =>
        logger.error(error)(s"${nodeType} - Setup failed") >>
          IO.pure(ToggleNodeResult.Failed)
      case Right(fiber) =>
        fiberRef.set(Some(fiber)) >>
          attemptJoinCluster(nodeType, fiberRef)
    }

  private def stopExistingNode(
    nodeType: NodeType,
    fiberRef: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]]
  ): IO[ToggleNodeResult] =
    for {
      leaveResult <- leaveCluster(nodeType).attempt
      _ <- leaveResult match {
        case Left(error) =>
          logger.error(error)(s"${nodeType} - Leave cluster error")
        case Right(false) =>
          logger.warn(s"${nodeType} - Leave cluster failed: Request succeeded but cluster leave failed")
        case Right(true) =>
          logger.info(s"${nodeType} - Left cluster: Successfully left the cluster")
      }
      _ <- stopAndClearNode(fiberRef)
      _ <- logger.info(s"${nodeType} - Node stopped")
    } yield ToggleNodeResult.Stopped

  private def attemptJoinCluster(
    nodeType: NodeType,
    fiberRef: Ref[IO, Option[Fiber[IO, Throwable, Nothing]]]
  ): IO[ToggleNodeResult] =
    for {
      _ <- logger.info(s"${nodeType} - Joining cluster: Waiting for node setup")
      _ <- IO.sleep(5.seconds)
      _ <- logger.info(s"${nodeType} - Running health check")
      healthCheck <- peerManager.getPeerHealthWithRetry(nodeType).attempt
      result <- healthCheck match {
        case Right(true) =>
          logger.info(s"${nodeType} - Health check passed, proceeding with cluster join") >>
            joinPeer(nodeType).attempt.flatMap {
              case Right(true) =>
                logger.info(s"${nodeType} - Join cluster success") >>
                  IO.pure(ToggleNodeResult.Started)
              case Right(false) =>
                logger.warn(s"${nodeType} - Join cluster failed") >>
                  stopAndClearNode(fiberRef) >>
                  IO.pure(ToggleNodeResult.Failed)
              case Left(error) =>
                logger.error(error)(s"${nodeType} - Join cluster error") >>
                  stopAndClearNode(fiberRef) >>
                  IO.pure(ToggleNodeResult.Failed)
            }
        case _ =>
          logger.info(s"${nodeType} - Health check failed, aborting cluster join") >>
            stopAndClearNode(fiberRef) >>
            IO.pure(ToggleNodeResult.Failed)
      }
    } yield result

  private def joinPeer(nodeType: NodeType): IO[Boolean] = {
    val config = getConfig(nodeType)
    val peerInfo = peerManager.getPeerInfo(nodeType)
    val peerToJoin = PeerToJoin(
      PeerId(Hex(peerInfo.id)),
      Host.fromString(peerInfo.host).get,
      Port.fromString(peerInfo.p2pPort).get
    )

    val uri = buildUri("127.0.0.1", config.cliPort, "cluster/join")
    client.successful(Request[IO](Method.POST, uri).withEntity(peerToJoin))
  }
}

object NodeRunner {
  def apply(
    client: Client[IO],
    peerManager: PeerManager,
    l0Config: L0NodeConfig,
    l1Config: L1NodeConfig
  ): NodeRunner =
    new NodeRunner(client, peerManager, l0Config, l1Config)
}
