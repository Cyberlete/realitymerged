package org.reality.infrastructure.rewards

import cats.data.StateT

import org.reality.ext.refined._
import org.reality.schema.balance.Amount
import org.reality.sdk.config.types.DTMConfig

trait DTMDistributor[F[_]] {
  def distribute(): DistributionState[F]
}

object DTMDistributor {

  def make(config: DTMConfig): DTMDistributor[Either[ArithmeticException, *]] =
    () =>
      StateT { amount: Amount =>
        for {
          numerator <- amount.value * config.dtmWeight
          denominator <- config.dtmWeight + config.remainingWeight
          dtmRewards <- numerator / denominator
          remainingRewards <- amount.value - dtmRewards
        } yield (Amount(remainingRewards), List(config.address -> Amount(dtmRewards)))
      }
}
