package org.reality.combined.node

import cats.effect.IO
import cats.implicits.toFunctorOps
import org.http4s._
import org.http4s.circe._
import org.reality.schema.node.{NodeInfo, NodeState}
import org.reality.schema.peer.PeerInfo
import org.reality.schema.transaction.{AddressBalance, TransactionReference}
import io.circe.{Decoder, Encoder}
import io.circe.Decoder.Result
import io.circe.HCursor
import org.http4s.client.Client
import org.http4s.util.CaseInsensitiveString

object HttpUtils {
  def buildUri(host: String, port: String, path: String): Uri =
    Uri.unsafeFromString(s"http://$host:$port/$path")

  def addJsonHeaders(request: Request[IO]): Request[IO] =
    request.withHeaders(
      Headers(
        Header.Raw(CaseInsensitiveString("Content-Type"), "application/json"),
        Header.Raw(CaseInsensitiveString("Accept"), "application/json")
      )
    )

  // Extension methods for http4s client
  implicit class ClientOps[F[_]](client: Client[F]) {
    def successful(request: Request[F])(implicit F: cats.effect.Async[F]): F[Boolean] =
      client.status(request).map(_.isSuccess)
  }

  // Decoders for NodeInfo
  implicit val nodeStateDecoder: Decoder[NodeState] = new Decoder[NodeState] {
    override def apply(c: HCursor): Result[NodeState] =
      c.as[String].map {
        case "Ready"    => NodeState.Ready
        case "Starting" => NodeState.Starting
        case "Stopping" => NodeState.Stopping
        case other      => throw new IllegalArgumentException(s"Unknown NodeState value: $other")
      }
  }

  // Manually define decoders since deriveDecoder isn't working
  implicit val nodeInfoDecoder: Decoder[NodeInfo] = new Decoder[NodeInfo] {
    override def apply(c: HCursor): Result[NodeInfo] =
      for {
        id <- c.downField("id").as[String]
        state <- c.downField("state").as[NodeState]
        // Add other fields as needed
      } yield new NodeInfo(id, state)
  }

  implicit val nodeInfoEntityDecoder: EntityDecoder[IO, NodeInfo] = jsonOf[IO, NodeInfo]

  // Other needed decoders
  implicit val addressBalanceDecoder: Decoder[AddressBalance] = new Decoder[AddressBalance] {
    override def apply(c: HCursor): Result[AddressBalance] =
      for {
        amount <- c.downField("amount").as[Long]
        // Add other fields as needed
      } yield new AddressBalance(amount)
  }

  implicit val addressBalanceEntityDecoder: EntityDecoder[IO, AddressBalance] = jsonOf[IO, AddressBalance]

  implicit val transactionReferenceDecoder: Decoder[TransactionReference] = new Decoder[TransactionReference] {
    override def apply(c: HCursor): Result[TransactionReference] =
      for {
        value <- c.downField("value").as[String]
        // Add other fields as needed
      } yield new TransactionReference(value)
  }

  implicit val transactionReferenceEntityDecoder: EntityDecoder[IO, TransactionReference] = jsonOf[IO, TransactionReference]

  implicit val peerInfoDecoder: Decoder[PeerInfo] = new Decoder[PeerInfo] {
    override def apply(c: HCursor): Result[PeerInfo] =
      for {
        id <- c.downField("id").as[String]
        host <- c.downField("host").as[String]
        p2pPort <- c.downField("p2pPort").as[String]
        // Add other fields as needed
      } yield new PeerInfo(id, host, p2pPort)
  }

  implicit val peerInfoSetEntityDecoder: EntityDecoder[IO, Set[PeerInfo]] = jsonOf[IO, Set[PeerInfo]]
}
