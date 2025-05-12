package org.reality.infrastructure.snapshot.programs

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.dag.snapshot.{Coinbase, GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.infrastructure.snapshot.{GlobalSnapshotLocalFileSystemStorage, GlobalSnapshotTraverse}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance
import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.consensus.SelectActivePeers
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotContextFunctions
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object RollbackLoader {

  def make[F[_]: Async](
    globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
    snapshotContextFunctions: GlobalSnapshotContextFunctions[F]
  ): RollbackLoader[F] =
    new RollbackLoader[F](
      globalSnapshotLocalFileSystemStorage,
      snapshotContextFunctions
    ) {}
}

sealed abstract class RollbackLoader[F[_]: Async] private (
  globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
  snapshotContextFunctions: GlobalSnapshotContextFunctions[F]
) {

  private val logger = Slf4jLogger.getLogger[F]

  def load(rollbackHash: Hash, balances: Map[Address, Balance], facilitatorId: PeerId): F[(GlobalSnapshotInfo, Signed[GlobalSnapshot])] =
    logger.info("Attempt to treat rollback hash as pointer to incremental global snapshot") >> {
      val snapshotTraverse = GlobalSnapshotTraverse
        .make[F](
          globalSnapshotLocalFileSystemStorage.read(_),
          getGenesisSnapshotInfo(balances, facilitatorId),
          snapshotContextFunctions,
          rollbackHash
        )
      snapshotTraverse.loadChain()
    }

  private def getGenesisSnapshotInfo(balances: Map[Address, Balance], facilitatorId: PeerId): Hash => F[Option[GlobalSnapshotInfo]] = {
    case hash if hash === Coinbase.hash =>
      GlobalSnapshotInfo(
        SortedMap.empty,
        SortedMap.empty,
        SortedMap.from(balances),
        SortedMap.empty,
        SortedMap.empty,
        candidates = SortedSet(facilitatorId),
        (SnapshotOrdinal(SelectActivePeers.selectionInterval), NonEmptySet.one(facilitatorId))
      ).some.pure[F]
    case _ => none.pure[F]
  }
}
