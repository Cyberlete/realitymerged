package org.reality.aci.endpoint

import cats.effect.Concurrent
import cats.implicits._

import org.reality.aci.ACIRegistry

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

class UploadJarEndpoint[F[_]: Concurrent](aciRegistry: ACIRegistry[F]) extends Http4sDsl[F] {

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "state-channel-jar" =>
        for {
          payload <- req.body.compile.toVector
          jar <- aciRegistry.createStateChannelJar(payload.toArray)
          result <- Created(s"State channel created under address ${jar.id}")
        } yield result
    }

}
