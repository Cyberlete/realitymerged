package org.reality.dag.snapshot

import cats.MonadThrow
import cats.data.NonEmptySet
import cats.syntax.contravariantSemigroupal._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.ext.crypto._
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction.{DeployAppTransactionInfo, RegisterAppProviderTransactionInfo, TransactionReference}
import org.reality.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  deployAppTransactionsInfo: SortedMap[String, DeployAppTransactionInfo],
  registerAppProviderTransactionsInfo: SortedMap[String, RegisterAppProviderTransactionInfo],
  candidates: SortedSet[PeerId],
  // TODO: we may need to add current facilitators for the rollback functionality
  // currentFacilitators: NonEmptySet[PeerId],
  nextRotationFacilitators: (SnapshotOrdinal, NonEmptySet[PeerId])
) {

  def stateProof[F[_]: MonadThrow]: F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hashF,
      lastTxRefs.hashF,
      balances.hashF,
      deployAppTransactionsInfo.hashF,
      registerAppProviderTransactionsInfo.hashF,
      candidates.hashF,
      nextRotationFacilitators.hashF
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, _, _, _, _))
}

object GlobalSnapshotInfo {
  def empty(nextRotatioOrdinal: SnapshotOrdinal, peerId: PeerId) = GlobalSnapshotInfo(
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    SortedMap.empty,
    SortedSet(peerId),
    (nextRotatioOrdinal, NonEmptySet.one(peerId))
  )
}
