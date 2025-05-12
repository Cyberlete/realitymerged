package org.reality.http.routes

import cats.effect.Async
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.domain.snapshot.GlobalSnapshotStorage
import org.reality.ext.codecs.BinaryCodec
import org.reality.ext.http4s.SnapshotOrdinalVar
import org.reality.ext.http4s.headers.negotiation.resolveEncoder
import org.reality.schema.SnapshotOrdinal
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Encoder
import io.circe.shapes._
import org.http4s.circe.CirceEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityEncoder, HttpRoutes}
import shapeless.HNil
import shapeless.syntax.singleton._

final case class GlobalSnapshotRoutes[F[_]: Async](
  globalSnapshotStorage: GlobalSnapshotStorage[F],
  clusterStorage: ClusterStorage[F]
) extends Http4sDsl[F] {
  import GlobalSnapshotRoutes._

  private val prefixPath = "/global-snapshots"

  implicit def binaryAndJsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    List(BinaryCodec.encoder[F, A], CirceEntityEncoder.circeEntityEncoder[F, A])

  private val httpRoutes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "latest" / "ordinal" =>
        import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

        globalSnapshotStorage.headSnapshot.map(_.map(_.ordinal)).flatMap {
          case Some(ordinal) => Ok(("value" ->> ordinal.value.value) :: HNil)
          case None          => NotFound()
        }

      case req @ GET -> Root / "latest" =>
        resolveEncoder[F, Signed[GlobalSnapshot]](req) { implicit enc =>
          globalSnapshotStorage.headSnapshot.flatMap {
            case Some(snapshot) => Ok(snapshot)
            case _              => NotFound()
          }
        }

      case req @ GET -> Root / "latest" / "combined" =>
        resolveEncoder[F, (Signed[GlobalSnapshot], GlobalSnapshotInfo)](req) { implicit enc =>
          globalSnapshotStorage.head.flatMap {
            case Some(snapshot) => Ok(snapshot)
            case _              => NotFound()
          }
        }

      case GET -> Root / "app-data" / appIdentifier =>
        import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
        globalSnapshotStorage.getDeployAppTransaction(appIdentifier).flatMap {
          case Some(value) => Ok(value)
          case _           => NotFound()
        }

      case GET -> Root / "app-provider" / appIdentifier =>
        import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
        globalSnapshotStorage.getAppProvider(appIdentifier).flatMap {
          case Some(value) => Ok(value)
          case _           => NotFound()
        }

      case req @ GET -> Root / SnapshotOrdinalVar(ordinal) =>
        resolveEncoder[F, Signed[GlobalSnapshot]](req) { implicit enc =>
          globalSnapshotStorage.get(ordinal).flatMap {
            case Some(snapshot) => Ok(snapshot)
            case _              => NotFound()
          }
        }

      case req @ GET -> Root / "range" :? StartQueryParam(start) +& EndQueryParam(end) =>
        resolveEncoder[F, List[Signed[GlobalSnapshot]]](req) { implicit enc =>
          val maybeOrdinals =
            (NonNegLong.from(start).toOption, NonNegLong.from(end).toOption).mapN {
              case (s, e) => (SnapshotOrdinal(s), SnapshotOrdinal(e))
            }

          maybeOrdinals match {
            case Some((startOrd, endOrd)) =>
              globalSnapshotStorage.getRange(startOrd, endOrd).flatMap(Ok(_))
            case None =>
              BadRequest("Invalid or missing parameters")
          }
        }
    }

  object GlobalSnapshotRoutes {
    object StartQueryParam extends QueryParamDecoderMatcher[Long]("start")
    object EndQueryParam extends QueryParamDecoderMatcher[Long]("end")
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
