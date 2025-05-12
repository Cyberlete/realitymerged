package org.reality.ext.collection

import java.util.concurrent.ConcurrentHashMap

import cats.Monad
import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import io.chrisdavenport.mapref.MapRef

object MapRefUtils {
  def ofConcurrentHashMap[F[_]: Sync, K, V](
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16
  ): MapRef[F, K, Option[V]] =
    MapRef.fromConcurrentHashMap(
      new ConcurrentHashMap[K, V](initialCapacity, loadFactor, concurrencyLevel)
    )

  implicit class MapRefOps[F[_]: Monad, K, V](val mapRef: MapRef[F, K, Option[V]]) {

    def ofConcurrentHashMap[Fi[_]: Sync, Ki, Vi](
      initialCapacity: Int = 16,
      loadFactor: Float = 0.75f,
      concurrencyLevel: Int = 16
    ): MapRef[Fi, Ki, Option[Vi]] =
      MapRef.fromConcurrentHashMap(
        new ConcurrentHashMap[Ki, Vi](initialCapacity, loadFactor, concurrencyLevel)
      )

    def toMap: F[Map[K, V]] =
      for {
        keys <- mapRef.keys
        keyValues <- keys.traverseFilter { id =>
          mapRef(id).get.map(_.map((id, _)))
        }
      } yield keyValues.toMap

    def clear: F[Unit] =
      for {
        keys <- mapRef.keys
        _ <- keys.traverse { id =>
          mapRef(id).set(none)
        }
      } yield ()
  }
}
