package org.reality.combined

import cats.effect.Async

import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class CoCellRoute[F[_]: Async](val coCellRoute: PartialFunction[Request[F], F[Response[F]]]) extends Http4sDsl[F] {
  private val prefixPath = "/state-channels"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]
  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F](coCellRoute)
  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
