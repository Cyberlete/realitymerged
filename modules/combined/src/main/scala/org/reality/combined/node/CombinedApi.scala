package org.reality.combined.node

import cats.effect.IO

import io.circe.Json
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoder
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpApp, HttpRoutes, Request}

case class CombinedApi(nodeApi: NodeApi) {
  private def handleRequest(request: Request[IO]): IO[Either[String, Json]] =
    (for {
      json <- request.as[Json]
      action <- IO.fromEither(json.hcursor.get[String]("action"))
      payload <- IO.fromEither(json.hcursor.get[Json]("payload"))
      apiResponse <- nodeApi.handleAction(action, payload)
    } yield Right(apiResponse)).handleErrorWith {
      case e: Exception => IO.pure(Left(e.getMessage))
      case _: Throwable => IO.pure(Left("An unknown error occurred"))
    }

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" =>
      handleRequest(req).flatMap {
        case Right(result)      => Ok(result)
        case Left(errorMessage) => BadRequest(Json.obj("error" -> Json.fromString(errorMessage)))
      }
  }

  val app: HttpApp[IO] = routes.orNotFound
}
