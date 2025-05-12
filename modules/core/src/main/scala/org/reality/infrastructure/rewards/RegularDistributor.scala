package org.reality.infrastructure.rewards

import cats.data.StateT
import cats.effect.Async
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet

import org.reality.ext.refined._
import org.reality.schema.ID.Id
import org.reality.schema.address
import org.reality.schema.balance.Amount
import org.reality.schema.peer.PeerId
import org.reality.security.{SecurityProvider, hex}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait RegularDistributor[F[_]] {
  def distribute(entropyRates: Map[PeerId, Double], facilitators: SortedSet[Id]): DistributionState[F]
}

object RegularDistributor {

  // TODO: Fix this
  def make[F[_]: Async: SecurityProvider]: RegularDistributor[F] =
    (entropyRates: Map[PeerId, Double], facilitators) =>
      StateT { amount =>
        facilitators.toList
          .traverse(f => f.toAddress.map((_, f.hex)))
          .map { addresses: Seq[(address.Address, hex.Hex)] =>
            for {
              (bottomAmount, reminder) <- amount.value /% NonNegLong.unsafeFrom(addresses.length.toLong)
              topAmount <- bottomAmount + 1L
              (topRewards, bottomRewards) = addresses
                .splitAt(reminder.toInt)
                .bimap(_.map(_ -> Amount(topAmount)), _.map(_ -> Amount(bottomAmount)))

              hexEntMap = entropyRates.map { case (pId, ent) => (pId.value, ent) }
              allRewards = addresses.map {
                case (address, hex) =>
                  (
                    address,
                    Amount(NonNegLong.unsafeFrom((math.abs(hexEntMap.getOrElse(hex, 0d).toLong + 2L)) / 2L))
                  )
              }.toList // todo remove magic numbers

            } yield (Amount(amount.coerce), allRewards)
          }
          .map(_.liftTo[F])
          .flatten
      }

}
