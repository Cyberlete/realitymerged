//package org.reality.infrastructure.rewards
//
//import cats.data.StateT
//
//import org.reality.ext.refined._
//import org.reality.schema.balance.Amount
//import org.reality.sdk.config.types.StardustConfig
//
//trait StardustCollectiveDistributor[F[_]] {
//  def distribute(): DistributionState[F]
//}
//
//object StardustCollectiveDistributor {
//
//  def make(config: StardustConfig): StardustCollectiveDistributor[Either[ArithmeticException, *]] =
//    () =>
//      StateT { amount =>
//        for {
//          numeratorPrimary <- amount.value * config.primaryWeight
//          numeratorSecondary <- amount.value * config.secondaryWeight
//          denominator <- (config.primaryWeight + config.secondaryWeight).flatMap(_ + config.remainingWeight)
//          primaryRewards <- numeratorPrimary / denominator
//          secondaryRewards <- numeratorSecondary / denominator
//          remainingRewards <- (amount.value - primaryRewards).flatMap(_ - secondaryRewards)
//        } yield
//          (
//            Amount(remainingRewards),
//            List(
//              config.addressPrimary -> Amount(primaryRewards),
//              config.addressSecondary -> Amount(secondaryRewards)
//            )
//          )
//      }
//}
