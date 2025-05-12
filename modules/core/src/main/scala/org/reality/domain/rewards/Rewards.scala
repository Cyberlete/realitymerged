package org.reality.domain.rewards

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.schema.ID.Id
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.balance.Amount
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction.{RewardTransaction, Transaction}

trait Rewards[F[_]] {

  def mintedDistribution(
    epochProgress: EpochProgress,
    facilitators: SortedSet[Id],
    entRates: Map[PeerId, Double]
  ): F[SortedSet[RewardTransaction]]

  def feeDistribution(
    snapshotOrdinal: SnapshotOrdinal,
    transactions: SortedSet[Transaction],
    facilitators: SortedSet[Id],
    entRates: Map[PeerId, Double]
  ): F[SortedSet[RewardTransaction]]

  def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount
}
