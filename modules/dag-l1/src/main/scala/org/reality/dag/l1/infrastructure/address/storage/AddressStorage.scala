package org.reality.dag.l1.infrastructure.address.storage

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.functor._

import org.reality.dag.l1.domain.address.storage.AddressStorage
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance

import eu.timepit.refined.auto._

object AddressStorage {
  def make[F[_]: Async]: F[AddressStorage[F]] =
    Ref.of[F, Map[Address, Balance]](Map.empty).map(make(_))

  def make[F[_]: Async](balances: Ref[F, Map[Address, Balance]]): AddressStorage[F] =
    new AddressStorage[F] {
      def getBalance(address: Address): F[Balance] =
        balances.get.map(_.get(address).getOrElse(Balance.empty))

      def updateBalances(addressBalances: Map[Address, Balance]): F[Unit] =
        balances.update(_ ++ addressBalances)

      def clean: F[Unit] =
        balances.set(Map.empty)
    }
}
