//package org.reality.http.routes
//
//import cats.effect._
//import cats.effect.unsafe.implicits.global
//
//import scala.reflect.runtime.universe.TypeTag
//
//import org.reality.coreKryoRegistrar
//import org.reality.domain.cluster.programs.TrustPush
//import org.reality.ext.kryo._
//import org.reality.kryo.KryoSerializer
//import org.reality.schema.generators._
//import org.reality.schema.peer.PeerId
//import org.reality.schema.trust.{InternalTrustUpdate, InternalTrustUpdateBatch, TrustInfo}
//import org.reality.sdk.domain.gossip.Gossip
//import org.reality.sdk.sdkKryoRegistrar
//
//import io.circe.Encoder
//import org.http4s.Method._
//import org.http4s._
//import org.http4s.client.dsl.io._
//import org.http4s.syntax.literals._
//import org.reality.sdk.infrastructure.trust.storage.TrustStorage
//import suite.HttpSuite
//
//object TrustRoutesSuite extends HttpSuite {
//  test("GET trust succeeds") {
//    val req = GET(uri"/trust")
//    val peer = (for {
//      peers <- peersGen()
//    } yield peers.head).sample.get
//
//    KryoSerializer
//      .forAsync[IO](sdkKryoRegistrar.union(coreKryoRegistrar))
//      .use { implicit kryoPool =>
//        for {
//          trust <- Ref[IO].of(Map.empty[PeerId, TrustInfo])
//          ts = TrustStorage.make[IO](trust)
//          gossip = new Gossip[IO] {
//            override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
//            override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
//          }
//          tp = TrustPush.make[IO](ts, gossip)
//          _ <- ts.updateTrust(
//            InternalTrustUpdateBatch(List(InternalTrustUpdate(peer.id, 0.5)))
//          )
//          routes = TrustRoutes[IO](ts, tp).p2pRoutes
//        } yield expectHttpStatus(routes, req)(Status.Ok)
//      }
//      .unsafeRunSync()
//  }
//}
