package org.reality.infrastructure

import cats.data.StateT

import org.reality.domain.rewards.RewardsDistributor
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount

package object rewards {

  type DistributionState[F[_]] = StateT[F, Amount, List[(Address, Amount)]]

  type SimpleRewardsDistributor = RewardsDistributor[Either[ArithmeticException, *]]

}
