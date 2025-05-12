package org.reality.dag.l1.domain.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance
import org.reality.schema.height.Height
import org.reality.sdk.domain.collateral.LatestBalances
import org.reality.sdk.domain.snapshot.Validator.isNextSnapshot
import org.reality.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.reality.security.Hashed

import fs2.Stream
import fs2.concurrent.SignallingRef

object LastGlobalSnapshotStorage {

  def make[F[_]: Async: Ref.Make]: F[LastGlobalSnapshotStorage[F] with LatestBalances[F]] =
    SignallingRef.of[F, Option[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]](None).map(make(_))

  def make[F[_]: MonadThrow](
    snapshotR: SignallingRef[F, Option[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]]
  ): LastGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastGlobalSnapshotStorage[F] with LatestBalances[F] {

      def set(snapshot: Hashed[GlobalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotR.modify {
          case Some((current, _)) if isNextSnapshot(current, snapshot.signed.value) =>
            ((snapshot, state).some, Applicative[F].unit)
          case other =>
            (other, MonadThrow[F].raiseError[Unit](new Throwable("Failure during setting new global snapshot!")))
        }.flatten

      def setInitial(snapshot: Hashed[GlobalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotR.modify {
          case None => ((snapshot, state).some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial snapshot! Encountered non empty "))
            )
        }.flatten

      def get: F[Option[Hashed[GlobalSnapshot]]] =
        snapshotR.get.map(_.map(_._1))

      def getCombined: F[Option[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]] = snapshotR.get

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] = get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        snapshotR.get.map(_.map(_._2.balances))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        snapshotR.discrete
          .map(_.map(_._2))
          .flatMap(_.fold[Stream[F, GlobalSnapshotInfo]](Stream.empty)(Stream(_)))
          .map(_.balances)
    }
}
