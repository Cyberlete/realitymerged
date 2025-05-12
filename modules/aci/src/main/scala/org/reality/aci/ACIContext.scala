package org.reality.aci

import cats.effect.{Async, Concurrent}
import cats.syntax.all._

import org.reality.aci.endpoint.{ReceiveInputEndpoint, UploadJarEndpoint}
import org.reality.ext.collection.MapRefUtils

import io.chrisdavenport.mapref.MapRef
import org.http4s._

class ACIContext[F[_]: Concurrent: Async] {

  val repository: ACIRepository[F] = new ACIRepository[F]("/tmp/aci.db")
  val runtimeCache: MapRef[F, String, Option[StateChannelRuntime]] =
    MapRefUtils.ofConcurrentHashMap()

  val runtimeLoader: RuntimeLoader[F] = new RuntimeLoader[F]()
  val registry: ACIRegistry[F] = new ACIRegistry[F](repository, runtimeLoader, runtimeCache)

  val receiveInputEndpoint: ReceiveInputEndpoint[F] =
    new ReceiveInputEndpoint[F](registry)

  val addACITypeEndpoint: UploadJarEndpoint[F] =
    new UploadJarEndpoint[F](registry)

  val aciRoutes: HttpRoutes[F] = receiveInputEndpoint.routes <+> addACITypeEndpoint.routes

}
