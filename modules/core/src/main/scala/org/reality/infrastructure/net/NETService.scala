package org.reality.infrastructure.net

import cats.effect.Async
import cats.syntax.functor._

import org.reality.dag.transaction.TransactionValidator.lockedAddresses
import org.reality.domain.net.NETService
import org.reality.domain.snapshot.GlobalSnapshotStorage
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance

import io.estatico.newtype.ops._

object NETService {

  def make[F[_]: Async](globalSnapshotStorage: GlobalSnapshotStorage[F]): NETService[F] =
    new NETService[F] {

      def getBalances: F[Option[(SnapshotOrdinal, Map[Address, Balance])]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            (snapshot.value.ordinal, state.balances.unsorted)
        })

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.getOrElse(address, Balance.empty)
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) => calculateTotalSupply(state.balances.values, snapshot.value.ordinal)
        })

      def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            calculateTotalSupply(state.balances.filterNot { case (a, _) => lockedAddresses.contains(a) }.values, snapshot.value.ordinal)
        })

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.size
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      private def calculateTotalSupply(balances: Iterable[Balance], ordinal: SnapshotOrdinal): (BigInt, SnapshotOrdinal) = {
        val empty = BigInt(Balance.empty.coerce.value)
        val supply = balances
          .foldLeft(empty) { (acc, b) =>
            acc + BigInt(b.coerce.value)
          }

        (supply, ordinal)
      }

    }
}
