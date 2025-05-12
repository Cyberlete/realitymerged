//package org.reality.domain.cluster.programs
//
//import cats.effect.Async
//
//import org.reality.sdk.domain.gossip.Gossip
//import org.reality.sdk.domain.trust.storage.TrustStorage
//
//object TrustPush {
//
//  def make[F[_]: Async](
//    trustStorage: TrustStorage[F],
//    gossip: Gossip[F]
//  ): TrustPush[F] = new TrustPush[F](trustStorage, gossip) {}
//
//}
//
//sealed abstract class TrustPush[F[_]: Async] private (
//  trustStorage: TrustStorage[F],
//  gossip: Gossip[F]
//) {
//
////  def publishUpdated(): F[Unit] =
////    for {
////      trust <- trustStorage.getPublicTrust
////      _ <- gossip.spread(trust)
////    } yield {}
//
//}
