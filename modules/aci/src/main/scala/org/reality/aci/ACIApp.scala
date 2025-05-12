package org.reality.aci

import cats.effect.{Resource, _}

import com.comcast.ip4s.Port
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

object ACIApp extends IOApp {

  val context: ACIContext[IO] = new ACIContext[IO]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- context.repository.initDb
      app <- ACIServer
        .resource(context)
        .use(_ => IO.never)
        .as(ExitCode.Success)
    } yield app
}

object ACIServer {

  def resource[F[_]: Async](context: ACIContext[F]): Resource[F, org.http4s.server.Server] = {
    val httpApp = Router("/" -> context.aciRoutes).orNotFound
    val port: Port = Port.fromInt(8080).getOrElse(throw new IllegalArgumentException("Invalid port"))

    EmberServerBuilder
      .default[F]
      .withPort(port)
      .withHttpApp(httpApp)
      .build
  }
}
