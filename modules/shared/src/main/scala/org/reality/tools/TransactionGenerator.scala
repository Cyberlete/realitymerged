package org.reality.tools

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.reality.ext.crypto._
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.tools.AddressParams

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.io.file.Files
import fs2.{Pure, Stream}

object TransactionGenerator {

  def createSignedTransaction[F[_]: Files: Async: Random: SecurityProvider, T <: Transaction](
    transactionBuilder: (Address, Address, TransactionAmount, TransactionFee, TransactionReference, TransactionSalt) => T,
    sourceAddressParams: AddressParams,
    destinationAddress: Address,
    amount: TransactionAmount,
    fee: TransactionFee
  ): F[Signed[Transaction]] =
    for {
      AddressParams(source, key, initialTxRef) <- Async[F].pure(sourceAddressParams)
      salt <- Random[F].nextLong.map(TransactionSalt.apply)
      transaction = transactionBuilder(source, destinationAddress, amount, fee, initialTxRef, salt)
      signedTransaction <- Signed.forAsyncJson[F, Transaction](transaction, sourceAddressParams.keyPair)
    } yield signedTransaction

  private val chunkMinSize = 100

  def infiniteTransactionStream[F[_]: Async: Random: SecurityProvider](
    chunkSize: PosInt,
    feeValue: NonNegLong,
    addressParams: NonEmptyList[AddressParams]
  ): Stream[F, Signed[Transaction]] =
    addressParams.reverse.map {
      case AddressParams(source, key, initialTxRef) =>
        val otherWallets = addressParams.filter(_.address =!= source).map(_.address)

        def tx(lastTxRef: TransactionReference): F[Signed[Transaction]] =
          for {
            destination <- Random[F]
              .shuffleList(otherWallets)
              .map(_.headOption)
              .flatMap(_.liftTo[F](new Throwable("Not enough wallets")))
            amount = TransactionAmount(PosLong.MinValue)
            fee = TransactionFee(feeValue)
            salt <- Random[F].nextLong.map(TransactionSalt.apply)
            tx = StandardTransaction(source, destination, amount, fee, lastTxRef, salt)
            signedTx <- tx.sign(key)
          } yield signedTx

        def txStream(lastRef: TransactionReference): Stream[F, Signed[Transaction]] =
          for {
            signedTx <- Stream.eval(tx(lastRef))
            txRef <- Stream.eval(signedTx.value.hashF.map(TransactionReference(signedTx.ordinal, _)))
            result <- Stream(signedTx) ++ txStream(txRef)
          } yield result

        txStream(initialTxRef).chunkN(chunkSize)
    }.foldLeft[Stream[F, Stream[Pure, Signed[Transaction]]]](Stream.constant(Stream.empty)) { (acc, s) =>
      acc.zipWith(s)((acc, currChunk) => acc.cons(currChunk))
    }.flatten
      .chunkMin(chunkMinSize)
      .unchunks
      .prefetch

}
