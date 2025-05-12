package org.reality.domain.rewards

import cats.data.NonEmptySet

import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.infrastructure.rewards.DistributionState
import org.reality.schema.ID.Id

trait RewardsDistributor[F[_]] {

  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]

}
