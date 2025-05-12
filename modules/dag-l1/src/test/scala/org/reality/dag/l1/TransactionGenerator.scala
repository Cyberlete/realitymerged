package org.reality.dag.l1

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

trait TransactionGenerator {

  def generateTransactions[F[_]: Async: SecurityProvider](
    src: Address,
    srcKey: KeyPair,
    dst: Address,
    count: PosInt,
    fee: TransactionFee = TransactionFee.zero,
    lastTxRef: Option[TransactionReference] = None
  ): F[NonEmptyList[Hashed[Transaction]]] = {
    def generate(src: Address, srcKey: KeyPair, dst: Address, lastTxRef: TransactionReference): F[Hashed[Transaction]] =
      forAsyncJson[F, Transaction](
        StandardTransaction(src, dst, TransactionAmount(1L), fee, lastTxRef, TransactionSalt(0L)),
        srcKey
      ).flatMap(_.toHashed[F])

    generate(src, srcKey, dst, lastTxRef.getOrElse(TransactionReference.empty)).flatMap { first =>
      (1 until count).toList.foldLeftM(NonEmptyList.one(first)) {
        case (txs, _) =>
          generate(src, srcKey, dst, TransactionReference(txs.head.ordinal, txs.head.hash)).map(txs.prepend)
      }
    }
  }.map(_.reverse)
}
