package org.reality.sdk.domain.snapshot.services

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.ext.cats.syntax.next._
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.peer.L0Peer
import org.reality.sdk.domain.cluster.storage.L0ClusterStorage
import org.reality.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.reality.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.reality.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0Service[F[_]] {
  def pullGlobalSnapshots: F[Either[(Hashed[GlobalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalSnapshot]]]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]]
}

object L0Service {

  def make[F[_]: Async: SecurityProvider](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastSnapshotStorage: LastGlobalSnapshotStorage[F],
    singlePullLimit: Option[PosLong]
  ): L0Service[F] =
    new L0Service[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalSnapshot]]] =
        l0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient
            .get(ordinal)(l0Peer)
            .flatMap(_.toHashedWithSignatureCheck)
            .flatMap(_.liftTo[F])
            .map(_.some)
        }.handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=$ordinal")
            .map(_ => none[Hashed[GlobalSnapshot]])
        }

      def pullGlobalSnapshots: F[Either[(Hashed[GlobalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalSnapshot]]]] =
        lastSnapshotStorage.getOrdinal.flatMap {
          _.fold {
            pullLatestSnapshotFromRandomPeer.map(_.asLeft[List[Hashed[GlobalSnapshot]]])
          } { lastStoredOrdinal =>
            for {
              l0Peer <- l0ClusterStorage.getRandomPeer
              latestOrdinal <- l0GlobalSnapshotClient.getLatestOrdinal.run(l0Peer)
              nextOrdinal = lastStoredOrdinal.next
              lastOrdinal = calculateLastOrdinal(nextOrdinal, latestOrdinal)
              pulled <- pullSnapshots(l0Peer, nextOrdinal, lastOrdinal)
            } yield pulled.toList.asRight[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]
          }
        }.handleErrorWith { e =>
          logger
            .warn(e)("Failure pulling global snapshots from random peer")
            .as(List.empty[Hashed[GlobalSnapshot]].asRight[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)])
        }

      private def pullLatestSnapshotFromRandomPeer: F[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)] =
        l0ClusterStorage.getRandomPeer >>= pullLatestSnapshotFromPeer

      private def pullLatestSnapshotFromPeer(l0Peer: L0Peer): F[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)] =
        l0GlobalSnapshotClient.getLatest(l0Peer).flatMap {
          case (snapshot, state) =>
            snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
        }

      private def calculateLastOrdinal(nextOrdinal: SnapshotOrdinal, latestOrdinal: SnapshotOrdinal): SnapshotOrdinal =
        SnapshotOrdinal(
          NonNegLong.unsafeFrom(
            latestOrdinal.value.value
              .min(
                singlePullLimit
                  .map(nextOrdinal.value.value + _.value)
                  .getOrElse(latestOrdinal.value.value)
              )
          )
        )

      private def pullSnapshots(
        l0Peer: L0Peer,
        nextOrdinal: SnapshotOrdinal,
        lastOrdinal: SnapshotOrdinal
      ): F[List[Hashed[GlobalSnapshot]]] = {
        val ordinals = List
          .range(nextOrdinal.value.value, lastOrdinal.value.value + 1)
          .map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o)))

        type Success = Hashed[GlobalSnapshot]
        type Result = List[Success]
        type Agg = (List[SnapshotOrdinal], Result)
        (ordinals, List.empty[Success]).tailRecM[F, Result] {
          case (ordinal :: nextOrdinals, snapshots) =>
            l0GlobalSnapshotClient
              .get(ordinal)(l0Peer)
              .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
              .map(s => (nextOrdinals, snapshots :+ s).asLeft[Result])
              .handleErrorWith { e =>
                logger
                  .warn(e)(s"Failure pulling snapshot with ordinal=${ordinal.show}")
                  .as(snapshots.asRight[Agg])
              }

          case (Nil, snapshots) => snapshots.asRight[Agg].pure[F]
        }
      }
    }
}
