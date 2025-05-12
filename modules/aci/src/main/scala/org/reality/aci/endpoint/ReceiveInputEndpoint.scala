package org.reality.aci.endpoint

import cats.effect._
import cats.implicits._

import org.reality.aci.ACIRegistry

import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{EntityDecoder, Request, Uri, _}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ReceiveInputEndpoint[F[_]: Async](
  registry: ACIRegistry[F]
) extends Http4sDsl[F] {
  private val logger = Slf4jLogger.getLogger[F]

  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]
  implicit val encoder: EntityEncoder[F, Array[Byte]] = EntityEncoder.byteArrayEncoder[F]

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "input" / address =>
        for {
          payload <- req.as[Array[Byte]]
          result <- registry
            .getStateChannelRuntime(address)
            .semiflatMap { runtime =>
              for {
                input <- runtime.deserializeInput(payload)
                bytes <- runtime.serialize(input)
                _ <- Async[F].start(forwardInput(address, bytes))
                ok <- Ok(s"Forwarding input for address $address")
              } yield ok
            }
            .getOrElseF(NotFound("ACI type not found"))
        } yield result
    }

  def forwardInput(address: String, payload: Array[Byte]): F[Unit] = {
    val req: Request[F] = Request[F](method = POST, uri = Uri.unsafeFromString(s"http://localhost:9000/input/$address"))
    EmberClientBuilder.default[F].build.use { client =>
      for {
        resp <- client.status(req.withEntity(payload))
        _ <- logger.debug(s"Response ${resp.code} for address $address")
      } yield ()
    }
  }

}
