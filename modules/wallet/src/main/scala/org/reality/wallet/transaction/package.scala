package org.reality.wallet

import java.nio.file.Files
import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.ext.crypto._
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.key.ops._
import org.reality.security.signature.Signed
import org.reality.security.{SecureRandom, SecurityProvider}
import org.reality.utils.binaryHash

import fs2.io.file.Path

package object transaction {

  def createTransaction[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    prevTx: Option[Signed[Transaction]],
    fee: TransactionFee,
    amount: TransactionAmount
  ): F[Signed[Transaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hashF.map(TransactionReference(tx.ordinal, _)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(TransactionSalt.apply)

      tx = StandardTransaction(source, destination, amount, fee, parent, salt)
      signedTx <- tx.sign(keyPair)

    } yield signedTx

  def createDeployAppTransaction[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    appDataPath: Path,
    appName: String,
    appVersion: String,
    appDescription: String,
    appDownloadURL: String,
    prevTx: Option[Signed[Transaction]],
    amount: TransactionAmount,
    fee: TransactionFee
  ): F[Signed[Transaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hashF.map(TransactionReference(tx.ordinal, _)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(TransactionSalt.apply)

      appDataBytes: Array[Byte] = Files.readAllBytes(appDataPath.toNioPath)
      appDataHash = binaryHash(appDataBytes)

      tx = DeployAppTransaction(
        source,
        destination,
        appDataHash,
        appName,
        appVersion,
        appDescription,
        appDownloadURL,
        fee,
        amount,
        parent,
        salt
      )
      signedTx <- tx.sign(keyPair)

    } yield signedTx

}
