package org.reality.aci

import cats.effect.Async
import cats.implicits.toSemigroupKOps

import org.reality.aci.endpoint.{ReceiveInputEndpoint, UploadJarEndpoint}

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ACIEndpoints[F[_]: Async](validateACIObjectEndpoint: ReceiveInputEndpoint[F], addACITypeEndpoint: UploadJarEndpoint[F])
    extends Http4sDsl[F] {

  protected val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  def routes(): HttpRoutes[F] =
    validateACIObjectEndpoint.routes <+> addACITypeEndpoint.routes

}
