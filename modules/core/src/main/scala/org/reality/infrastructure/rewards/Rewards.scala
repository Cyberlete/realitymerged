package org.reality.infrastructure.rewards

import cats.data._
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.domain.rewards._
import org.reality.ext.refined._
import org.reality.schema.ID.Id
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction.{RewardTransaction, TransactionAmount}
import org.reality.schema.{SnapshotOrdinal, transaction}
import org.reality.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops.toCoercibleIdOps

object Rewards {

  def make[F[_]: Async](
    rewardsPerEpoch: SortedMap[EpochProgress, Amount],
    regular: RegularDistributor[F]
  ): Rewards[F] =
    new Rewards[F] {

      def feeDistribution(
        snapshotOrdinal: SnapshotOrdinal,
        transactions: SortedSet[transaction.Transaction],
        facilitators: SortedSet[Id],
        entRates: Map[PeerId, Double]
      ): F[SortedSet[RewardTransaction]] = {

        val totalFee = transactions.toList
          .map(_.fee)
          .foldM(NonNegLong.MinValue) { case (acc, fee) => acc + fee.value }
          .map(Amount(_))
          .liftTo[F]

        totalFee.flatMap { amount: Amount =>
          regular
            .distribute(entRates, facilitators)
            .run(amount)
        }.map(toTransactions)
      }

      def mintedDistribution(
        epochProgress: EpochProgress,
        facilitators: SortedSet[Id],
        entRates: Map[PeerId, Double]
      ): F[SortedSet[RewardTransaction]] = {
        val amount = getAmountByEpoch(epochProgress, rewardsPerEpoch)

        def eitherToF[A](either: Either[ArithmeticException, A]): F[A] = either.liftTo[F]

        val allRewardsState = for {
          randomizer <- StateT.liftF(Random.scalaUtilRandomSeedLong(epochProgress.coerce))
          regularRewards <- regular.distribute(entRates, facilitators)
        } yield regularRewards

        allRewardsState
          .run(amount)
          .map(toTransactions)
      }

      private def validateState(totalPool: Amount)(state: (Amount, List[(Address, Amount)])): F[Unit] = {
        val (remaining, _) = state

        new RuntimeException(s"Remainder exists in distribution {totalPool=${totalPool.show}, remainingAmount=${remaining.show}}")
          .raiseError[F, Unit]
          .whenA(remaining =!= Amount.empty)
      }

      private def toTransactions(state: (Amount, List[(Address, Amount)])): SortedSet[RewardTransaction] = {
        val (_, rewards) = state

        rewards.flatMap {
          case (address, amount) =>
            refineV[Positive](amount.coerce.value).toList.map(a => RewardTransaction(address, TransactionAmount(a)))
        }.toSortedSet
      }

      def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount =
        rewardsPerEpoch
          .minAfter(epochProgress)
          .map { case (_, reward) => reward }
          .getOrElse(Amount.empty)
    }
}
