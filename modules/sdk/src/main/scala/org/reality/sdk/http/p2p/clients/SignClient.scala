package org.reality.sdk.http.p2p.clients

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.schema.peer.{JoinRequest, RegistrationRequest, SignRequest}
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed

import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{Request, Status}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait SignClient[F[_]] {
  def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]]
  def joinRequest(jr: JoinRequest): PeerResponse[F, Boolean]
  def getRegistrationRequest: PeerResponse[F, RegistrationRequest]
}

object SignClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F]): SignClient[F] =
    new SignClient[F] with Http4sClientDsl[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def getRegistrationRequest: PeerResponse[F, RegistrationRequest] =
        PeerResponse("registration/request")(client)

      def joinRequest(jr: JoinRequest): PeerResponse[F, Boolean] =
        PeerResponse("cluster/join", POST)(client) { (req: Request[F], c: Client[F]) =>
          val withEntity = req.withEntity(jr).toString()
          c.run(req.withEntity(jr)).use {
            case Status.Successful(_) => Applicative[F].pure(true) // .flatTap(msg => logger.error(s"Join request rejected due to: $msg"))
            case res =>
              res
                .as[String]
                .flatTap(msg =>
                  logger.error(s"Join request rejected due to msg $msg with req: $req and jr ${jr.toString} and withEntity $withEntity")
                )
                .as(false)
          }
        }

      def sign(signRequest: SignRequest): PeerResponse[F, Signed[SignRequest]] =
        PeerResponse("registration/sign", POST)(client) { (req, c) =>
          c.expect[Signed[SignRequest]](req.withEntity(signRequest))
        }
    }
}
