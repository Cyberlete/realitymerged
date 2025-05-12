package org.reality.combined.node

import cats.effect.IO
import cats.effect.std.Random

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.node.{NodeInfo, NodeState}
import org.reality.schema.peer.PeerInfo
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.hex.Hex
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed
import org.reality.tools.AddressParams
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.reality.schema.transaction.StandardTransaction
import io.circe.Json
import io.circe.generic.decoding.DerivedDecoder.deriveDecoder

import org.http4s._
import org.http4s.circe.jsonDecoder
import org.http4s.client.Client

class NodeService(
  client: Client[IO],
  nodeRunner: Option[NodeRunner],
  val peerManager: PeerManager
) {
  import HttpUtils._
  import Parsing._
  import Transactions._

  private def withNodeRunner[A](f: NodeRunner => IO[A]): IO[A] =
    nodeRunner match {
      case Some(runner) => f(runner)
      case None         => IO.raiseError(new Exception("NodeRunner not initialized"))
    }
  def getSelfCluster(nodeType: NodeType): IO[Set[PeerInfo]] =
    withNodeRunner { runner =>
      val config = runner.getConfig(nodeType)
      val uri = buildUri("127.0.0.1", config.publicPort, "cluster/info")
      val request = addJsonHeaders(Request[IO](Method.GET, uri))
      client.expect[Set[PeerInfo]](request)
    }

  def fetchAddressBalance(nodeType: NodeType, address: String): IO[AddressBalance] =
    for {
      connectionInfo <- getActiveConnectionInfo(nodeType)
      uri = buildUri(connectionInfo.host, connectionInfo.publicPort, s"net/$address/balance")
      request = addJsonHeaders(Request[IO](Method.GET, uri))
      response <- client
        .expect[AddressBalance](request)
        .onError { e =>
          IO.println(s"[ERROR] Failed to fetch balance for address $address: ${e.getMessage}")
        }
    } yield response

  def fetchLatestOrdinal(): IO[SnapshotOrdinal] =
    for {
      connectionInfo <- getActiveConnectionInfo(NodeType.L0)
      uri = buildUri(connectionInfo.host, connectionInfo.publicPort, "global-snapshots/latest/ordinal")
      request = addJsonHeaders(Request[IO](Method.GET, uri))
      response <- client
        .expect[SnapshotOrdinal](request)
        .onError { e =>
          IO.println(s"[ERROR] Failed to fetch latest ordinal: ${e.getMessage}")
        }
    } yield response

  def fetchLatestCombined(): IO[(Signed[GlobalSnapshot], GlobalSnapshotInfo)] =
    for {
      connectionInfo <- getActiveConnectionInfo(NodeType.L0)
      uri = buildUri(connectionInfo.host, connectionInfo.publicPort, "global-snapshots/latest/combined")
      request = addJsonHeaders(Request[IO](Method.GET, uri))
      response <- client
        .expect[(Signed[GlobalSnapshot], GlobalSnapshotInfo)](request)
        .onError { e =>
          IO.println(s"[ERROR] Failed to fetch latest combined snapshot: ${e.getMessage}")
        }
    } yield response

  def fetchSnapshotByOrdinal(ordinal: Long): IO[Signed[GlobalSnapshot]] =
    for {
      snapshotOrdinal <- Parsing.parseOrdinal(ordinal)
      response <- doFetchSnapshotByOrdinal(snapshotOrdinal)
    } yield response

  private def doFetchSnapshotByOrdinal(ordinal: SnapshotOrdinal): IO[Signed[GlobalSnapshot]] =
    for {
      connectionInfo <- getActiveConnectionInfo(NodeType.L0)
      uri = buildUri(connectionInfo.host, connectionInfo.publicPort, s"global-snapshots/${ordinal.value}")
      request = addJsonHeaders(Request[IO](Method.GET, uri))
      response <- client
        .expect[Signed[GlobalSnapshot]](request)
        .onError { e =>
          IO.println(s"[ERROR] Failed to fetch snapshot for ordinal ${ordinal.value}: ${e.getMessage}")
        }
    } yield response

  def fetchSnapshotRange(startOrdinal: Long, endOrdinal: Long): IO[List[Signed[GlobalSnapshot]]] =
    for {
      parsedStart <- Parsing.parseOrdinal(startOrdinal)
      parsedEnd <- Parsing.parseOrdinal(endOrdinal)
      response <- doFetchSnapshotRange(parsedStart, parsedEnd)
    } yield response

  private def doFetchSnapshotRange(
    startOrdinal: SnapshotOrdinal,
    endOrdinal: SnapshotOrdinal
  ): IO[List[Signed[GlobalSnapshot]]] =
    for {
      connectionInfo <- getActiveConnectionInfo(NodeType.L0)
      uri = buildUri(
        connectionInfo.host,
        connectionInfo.publicPort,
        s"global-snapshots/range?start=${startOrdinal.value}&end=${endOrdinal.value}"
      )
      request = addJsonHeaders(Request[IO](Method.GET, uri))
      response <- client
        .expect[List[Signed[GlobalSnapshot]]](request)
        .onError { e =>
          IO.println(
            s"[ERROR] Failed to fetch snapshot range ${startOrdinal.value}-${endOrdinal.value}: ${e.getMessage}"
          )
        }
    } yield response

  private def isNodeRunningAndReady(nodeType: NodeType): IO[Boolean] =
    nodeRunner match {
      case Some(runner) =>
        runner.isActive(nodeType).flatMap {
          case true =>
            val config = runner.getConfig(nodeType)
            val uri = buildUri("127.0.0.1", config.publicPort, "node/info")
            val request = addJsonHeaders(Request[IO](Method.GET, uri))
            client
              .expect[NodeInfo](request)
              .map(_.state == NodeState.Ready)
              .handleError(_ => false)
          case false =>
            IO.pure(false)
        }
      case None =>
        IO.pure(false)
    }

  private def getActiveConnectionInfo(nodeType: NodeType): IO[BasicConnectionInfo] =
    isNodeRunningAndReady(nodeType).flatMap {
      case true =>
        val config = nodeRunner.get.getConfig(nodeType)
        IO.pure(
          ConnectionInfo(
            host = "127.0.0.1",
            publicPort = config.publicPort,
            p2pPort = config.p2pPort
          )
        )
      case false =>
        IO.pure(peerManager.getPeerInfo(nodeType))
    }

  def sendStandardTransaction(
    privateKey: String,
    destinationAddressStr: String,
    amount: Long,
    fee: Long
  ): IO[String] =
    SecurityProvider.forAsync[IO].use { implicit sp =>
      Random.scalaUtilRandom[IO].flatMap { implicit random =>
        for {
          pkHex <- IO.pure(Hex(privateKey))
          sourceKey <- pkHex.toKeyPairFromPrivate[IO]
          sourceAddress = sourceKey.getPublic.toAddress

          connectionInfo <- getActiveConnectionInfo(NodeType.L1)

          sourceLastTxRef <- {
            val slug = s"transactions/last-reference/${sourceAddress.value}"
            val uri = buildUri(connectionInfo.host, connectionInfo.publicPort, slug)
            val request = addJsonHeaders(Request[IO](Method.GET, uri))
            client.expect[TransactionReference](request)
          }

          sourceAddressParam = AddressParams(sourceAddress, sourceKey, sourceLastTxRef)
          destinationAddress <- Parsing.parseAddress(destinationAddressStr)

          refinedAmount <- Parsing.parsePositiveAmount(amount)
          refinedFee <- Parsing.parseNonNegativeAmount(fee)

          signedTx <- Transactions.createSignedTransaction[IO, StandardTransaction](
            (source, destination, amount, fee, parent, salt) => StandardTransaction(source, destination, amount, fee, parent, salt),
            sourceAddressParam,
            destinationAddress,
            TransactionAmount(refinedAmount),
            TransactionFee(refinedFee)
          )

          uri = buildUri(connectionInfo.host, connectionInfo.publicPort, "transactions")
          request = addJsonHeaders(Request[IO](Method.POST, uri).withEntity(signedTx))
          response <- client.expect[Json](request).map(_.noSpaces)
        } yield response
      }
    }
}
