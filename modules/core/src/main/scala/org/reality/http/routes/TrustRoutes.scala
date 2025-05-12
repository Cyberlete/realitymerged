//package org.reality.http.routes
//
//import cats.effect.Async
//
//import org.reality.domain.cluster.programs.TrustPush
//import org.reality.kryo.KryoSerializer
//import org.reality.sdk.domain.trust.storage.TrustStorage
//
//import org.http4s.dsl.Http4sDsl
//
//final case class TrustRoutes[F[_]: Async: KryoSerializer](
//  trustStorage: TrustStorage[F],
//  trustPush: TrustPush[F]
//) extends Http4sDsl[F] {
//  private[routes] val prefixPath = "/trust"
//
////  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
////    case GET -> Root =>
////      trustStorage.getPublicTrust.flatMap { publicTrust =>
////        Ok(publicTrust)
////      }
////  }
////
////  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
////    case req @ POST -> Root =>
////      req.decodeR[InternalTrustUpdateBatch] { trustUpdates =>
////        trustStorage
////          .updateTrust(trustUpdates)
////          .flatMap(_ => trustPush.publishUpdated())
////          .flatMap(_ => Ok())
////          .recoverWith {
////            case _ =>
////              Conflict(s"Internal trust update failure")
////          }
////      }
////  }
////
////  val p2pRoutes: HttpRoutes[F] = Router(
////    prefixPath -> p2p
////  )
////
////  val cliRoutes: HttpRoutes[F] = Router(
////    prefixPath -> cli
////  )
//}
