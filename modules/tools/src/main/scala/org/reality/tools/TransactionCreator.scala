package org.reality.tools

import java.nio.file.{Files => JFiles}

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.tools.TransactionGenerator.createSignedTransaction
import org.reality.tools.cli.method.{SendDeployAppTransactionBasicOpts, SendStandardTransactionBasicOpts}
import org.reality.utils.binaryHash

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import fs2.io.file.Files

object TransactionCreator {

  def createSignedStandardTransaction[F[_]: Files: Async: Random: SecurityProvider](
    standardTransactionBasicOpts: SendStandardTransactionBasicOpts,
    sourceAddressParams: AddressParams,
    destinationAddress: Address
  ): F[Signed[Transaction]] =
    createSignedTransaction[F, StandardTransaction](
      (source, destination, amount, fee, parent, salt) => StandardTransaction(source, destination, amount, fee, parent, salt),
      sourceAddressParams,
      destinationAddress,
      TransactionAmount(standardTransactionBasicOpts.amount),
      TransactionFee(standardTransactionBasicOpts.fee)
    )

  def createSendDeployAppTransaction[F[_]: Files: Async: Random: SecurityProvider](
    sendDeployAppTransactionBasicOpts: SendDeployAppTransactionBasicOpts,
    sourceAddressParam: AddressParams,
    destinationAddress: Address
  ): F[Signed[Transaction]] =
    for {
      appDataBytes <- Async[F].delay(JFiles.readAllBytes(sendDeployAppTransactionBasicOpts.appDataPath))
      deployAppDataHash = binaryHash(appDataBytes)

      signedDeployAppTx <- createSignedTransaction[F, DeployAppTransaction](
        (source, destination, _, fee, parent, salt) =>
          DeployAppTransaction(
            source,
            destination,
            deployAppDataHash,
            sendDeployAppTransactionBasicOpts.appName,
            sendDeployAppTransactionBasicOpts.appVersion,
            sendDeployAppTransactionBasicOpts.appDescription,
            sendDeployAppTransactionBasicOpts.appDownloadUrl,
            fee,
            TransactionAmount(PosLong.MinValue),
            parent,
            salt
          ),
        sourceAddressParam,
        destinationAddress,
        TransactionAmount(PosLong.MinValue), // Fixed amount for deploy app
        TransactionFee(sendDeployAppTransactionBasicOpts.fee)
      )
    } yield signedDeployAppTx

  def randomString(length: Int) = scala.util.Random.alphanumeric.take(length).mkString

}
