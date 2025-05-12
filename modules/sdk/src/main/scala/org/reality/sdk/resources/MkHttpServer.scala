package org.reality.sdk.resources

import java.net.BindException

import cats.effect.kernel.{Async, Resource}
import cats.implicits.{catsSyntaxApply, catsSyntaxFlatMapOps}
import cats.syntax.show._

import org.reality.sdk.config.types.HttpServerConfig
import org.reality.sdk.resources.MkHttpServer.ServerName

import derevo.cats.show
import derevo.derive
import io.estatico.newtype.macros.newtype
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait MkHttpServer[F[_]] {
  def newEmber(name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]): Resource[F, Server]
}

object MkHttpServer {

  def apply[F[_]: MkHttpServer]: MkHttpServer[F] = implicitly

  @derive(show)
  @newtype
  case class ServerName(value: String)

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  private def showEmberBanner[F[_]: Async](name: ServerName, cfg: HttpServerConfig)(s: Server): F[Unit] =
    logger.info(s"HTTP Server name=${name.show} started at ${s.address}:${cfg.port}")

  implicit def forAsync[F[_]: Async]: MkHttpServer[F] =
    new MkHttpServer[F] {
      override def newEmber(name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]): Resource[F, Server] =
        Resource.eval {
          logger[F].info(s"Attempting to start HTTP Server name=${name.value} on host=${cfg.host} and port=${cfg.port}")
        } >> EmberServerBuilder
          .default[F]
          .withHost(cfg.host)
          .withPort(cfg.port)
          .withShutdownTimeout(cfg.shutdownTimeout)
          .withHttpApp(httpApp)
          .build
          .evalTap(showEmberBanner[F](name, cfg))
          .attempt
          .flatMap {
            case Left(e: BindException) =>
              val errMsg =
                s"BindException caught while attempting to start HTTP Server name=${name.value} on host=${cfg.host} and port=${cfg.port}"
              Resource.eval(logger[F].error(errMsg) *> Async[F].raiseError[Server](new BindException(errMsg).initCause(e)))

            case Left(e) =>
              Resource.eval(
                logger[F].error(s"An unexpected error occurred while starting HTTP Server name=${name.value}") *> Async[F]
                  .raiseError[Server](e)
              )

            case Right(server) =>
              Resource.eval(logger[F].info(s"Successfully started HTTP Server name=${name.value}") *> Async[F].pure(server))
          }
    }
}
