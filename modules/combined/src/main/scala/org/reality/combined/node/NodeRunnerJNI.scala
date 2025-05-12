package org.reality.combined.node

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import io.circe.JsonObject
import io.circe.generic.auto.exportEncoder
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.reality.combined.node.ToggleNodeResult.Failed
import org.reality.keytool.KeyPairGenerator
import org.reality.security.SecurityProvider
import org.reality.security.key.ops.{PrivateKeyOps, PublicKeyOps}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object NodeRunnerJNI {
  private case class NodeRunnerState(
    nodeService: Option[NodeService] = None,
    nodeRunner: Option[NodeRunner] = None,
    peerManager: Option[PeerManager] = None
  )

  private val state = new java.util.concurrent.atomic.AtomicReference(NodeRunnerState())
  private lazy val clientResource: Resource[IO, Client[IO]] = EmberClientBuilder.default[IO].build
  private lazy val client: Client[IO] = clientResource.allocated.unsafeRunSync()._1
  private val logger = Slf4jLogger.getLogger[IO]

  @throws(classOf[Exception])
  def initializePeerManager(
    l0PeerInfoJson: String,
    l1PeerInfoJson: String
  ): Unit =
    if (state.get().peerManager.isEmpty) {
      val l0PeerInfo = decode[PeerInfo](l0PeerInfoJson) match {
        case Right(info) => info
        case Left(error) => throw new Exception("initializePeerManager: Failed to parse l0PeerInfoJson", error)
      }

      val l1PeerInfo = decode[PeerInfo](l1PeerInfoJson) match {
        case Right(info) => info
        case Left(error) => throw new Exception("initializePeerManager: Failed to parse l1PeerInfoJson", error)
      }

      val pm = new PeerManager(client, l0PeerInfo, l1PeerInfo)
      val _ = state.updateAndGet { s =>
        val newPm = Some(pm)
        val newService = Some(new NodeService(client, s.nodeRunner, pm))
        s.copy(peerManager = newPm, nodeService = newService)
      }
    }

  @throws(classOf[Exception])
  def initializeNodeRunner(
    l0ConfigJson: String,
    l1ConfigJson: String
  ): Unit =
    if (state.get().nodeRunner.isEmpty) {
      val l0Config = decode[L0NodeConfig](l0ConfigJson) match {
        case Right(config) => config
        case Left(error)   => throw new Exception("initializeNodeRunner: Failed to parse l0ConfigJson", error)
      }

      val l1Config = decode[L1NodeConfig](l1ConfigJson) match {
        case Right(config) => config
        case Left(error)   => throw new Exception("initializeNodeRunner: Failed to parse l1ConfigJson", error)
      }

      val pm = getPeerManager
      val runner = new NodeRunner(client, pm, l0Config, l1Config)
      val _ = state.updateAndGet { s =>
        val newRunner = Some(runner)
        val newService = Some(new NodeService(client, newRunner, pm))
        s.copy(nodeRunner = newRunner, nodeService = newService)
      }
    }

  @throws(classOf[Exception])
  def getSelfCluster(nodeTypeStr: String): String = {
    val nodeType = parseNodeType(nodeTypeStr)
    getNodeService
      .getSelfCluster(nodeType)
      .map(_.asJson.noSpaces)
      .unsafeRunSync()
  }

  @throws(classOf[Exception])
  def getPeerHealth(nodeTypeStr: String): Boolean = {
    val nodeType = parseNodeType(nodeTypeStr)
    getPeerManager.getPeerHealth(nodeType).unsafeRunSync()
  }

  @throws(classOf[Exception])
  def fetchAddressBalance(nodeTypeStr: String, address: String): String = {
    val nodeType = parseNodeType(nodeTypeStr)
    getNodeService
      .fetchAddressBalance(nodeType, address)
      .map(_.asJson.noSpaces)
      .unsafeRunSync()
  }

  @throws(classOf[Exception])
  def fetchLatestOrdinal(): String =
    getNodeService
      .fetchLatestOrdinal()
      .map(_.asJson.noSpaces)
      .unsafeRunSync()

  @throws(classOf[Exception])
  def fetchLatestCombined(): String =
    getNodeService
      .fetchLatestCombined()
      .map(_.asJson.noSpaces)
      .unsafeRunSync()

  @throws(classOf[Exception])
  def fetchSnapshotByOrdinal(ordinal: Long): String =
    getNodeService
      .fetchSnapshotByOrdinal(ordinal)
      .map(_.asJson.noSpaces)
      .unsafeRunSync()

  @throws(classOf[Exception])
  def fetchSnapshotRange(startOrdinal: Long, endOrdinal: Long): String =
    getNodeService
      .fetchSnapshotRange(startOrdinal, endOrdinal)
      .map(_.asJson.noSpaces)
      .unsafeRunSync()

  @throws(classOf[Exception])
  def sendStandardTransaction(
    privateKey: String,
    destinationAddress: String,
    amount: Long,
    fee: Long
  ): String =
    getNodeService.sendStandardTransaction(privateKey, destinationAddress, amount, fee).unsafeRunSync()

  @throws(classOf[Exception])
  def toggleNode(nodeTypeStr: String): String = {
    val nodeType = parseNodeType(nodeTypeStr)
    getNodeRunner
      .toggleNode(nodeType)
      .map(_.asJson.noSpaces)
      .handleErrorWith(error =>
        logger.error(error)(s"Critical error in toggle $nodeType") >>
          IO.pure(identity[ToggleNodeResult](Failed).asJson.noSpaces)
      )
      .unsafeRunSync()
  }

  @throws(classOf[Exception])
  def generateKeyPair(): String =
    generateKeyPairInner().unsafeRunSync()

  private def generateKeyPairInner(): IO[String] =
    SecurityProvider.forAsync[IO].use { implicit sp =>
      for {
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        json = JsonObject(
          "publicKey" -> keyPair.getPublic.toAddress.asJson,
          "privateKey" -> keyPair.getPrivate.toHex.asJson
        ).asJson.noSpaces
      } yield json
    }

  private def getPeerManager: PeerManager = state.get().peerManager.getOrElse {
    throw new Exception("PeerManager is not initialized. Call initializePeerManager() first.")
  }

  private def getNodeService: NodeService = state.get().nodeService.getOrElse {
    throw new Exception("NodeService is not initialized. Call initializePeerManager() first.")
  }

  private def getNodeRunner: NodeRunner = state.get().nodeRunner.getOrElse {
    throw new Exception("NodeRunner is not initialized. Call initializeNodeRunner() first.")
  }

  private def parseNodeType(nodeTypeStr: String): NodeType = nodeTypeStr match {
    case "L0" => NodeType.L0
    case "L1" => NodeType.L1
    case _    => throw new Exception(s"Invalid node type: $nodeTypeStr")
  }
}
