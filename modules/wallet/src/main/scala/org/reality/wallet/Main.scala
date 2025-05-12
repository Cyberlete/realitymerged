package org.reality.wallet

import java.security.KeyPair

import cats.MonadThrow
import cats.effect.std.Console
import cats.effect.{Async, ExitCode, IO}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.BuildInfo
import org.reality.keytool.KeyStoreUtils
import org.reality.schema.address.Address
import org.reality.schema.transaction.{Transaction, TransactionAmount, TransactionFee}
import org.reality.security.SecurityProvider
import org.reality.security.key.ops._
import org.reality.security.signature.Signed
import org.reality.wallet.cli.env.EnvConfig
import org.reality.wallet.cli.method._
import org.reality.wallet.transaction.io.{readFromJsonFile, writeToJsonFile}
import org.reality.wallet.transaction.{createDeployAppTransaction, createTransaction}

import com.monovore.decline._
import com.monovore.decline.effect._
import fs2.io.file.Path
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Reality Wallet",
      version = BuildInfo.version
    ) {
  implicit val logger = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    (cli.method.opts, cli.env.opts).mapN {
      case (method, envs) =>
        SecurityProvider.forAsync[IO].use { implicit sp =>
          loadKeyPair[IO](envs).flatMap { keyPair =>
            method match {
              case ShowAddress() =>
                showAddress[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing address."))
                  .as(ExitCode.Success)
              case ShowId() =>
                showId[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing id."))
                  .as(ExitCode.Success)
              case ShowPublicKey() =>
                showPublicKey[IO](keyPair)
                  .handleErrorWith(err => logger.error(err)(s"Error while showing public key."))
                  .as(ExitCode.Success)
              case CreateTransaction(destination, fee, amount, prevTxPath, nextTxPath) =>
                createAndStoreTransaction[IO](keyPair, destination, fee, amount, prevTxPath, nextTxPath)
                  .handleErrorWith(err => logger.error(err)(s"Error while creating transaction."))
                  .as(ExitCode.Success)
              case CreateDeployAppTransaction(
                    destination,
                    appDataPath,
                    appName,
                    appVersion,
                    appDescription,
                    appDownloadURL,
                    fee,
                    amount,
                    prevTxPath,
                    nextTxPath
                  ) =>
                createAndStoreDeployAppTransaction[IO](
                  keyPair,
                  destination,
                  appDataPath,
                  appName,
                  appVersion,
                  appDescription,
                  appDownloadURL,
                  fee,
                  amount,
                  prevTxPath,
                  nextTxPath
                )
                  .handleErrorWith(err => logger.error(err)(s"Error while creating deploy app transaction."))
                  .as(ExitCode.Success)
            }
          }
        }
    }

  private def showAddress[F[_]: Console](keyPair: KeyPair): F[Unit] =
    Console[F].println(keyPair.getPublic.toAddress)

  private def showId[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic.toId.hex.value)

  private def showPublicKey[F[_]: Console](keyPair: KeyPair): F[Unit] = Console[F].println(keyPair.getPublic)

  private def createAndStoreTransaction[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    fee: TransactionFee,
    amount: TransactionAmount,
    prevTxPath: Option[Path],
    nextTxPath: Path
  ): F[Unit] =
    for {
      logger <- Slf4jLogger.create[F]

      prevTx <- prevTxPath match {
        case Some(path) =>
          readFromJsonFile(path)
            .handleErrorWith(e =>
              logger.error(e)(s"Error while reading previous transaction from path $path") >> MonadThrow[F]
                .raiseError[Option[Signed[Transaction]]](e)
            )
        case None => None.pure[F]
      }

      tx <- createTransaction(keyPair, destination, prevTx, fee, amount)

      _ <- writeToJsonFile(nextTxPath)(tx)
    } yield ()

  private def createAndStoreDeployAppTransaction[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    appDataPath: Path,
    appName: String,
    appVersion: String,
    appDescription: String,
    appDownloadURL: String,
    fee: TransactionFee,
    amount: TransactionAmount,
    prevTxPath: Option[Path],
    nextTxPath: Path
  ): F[Unit] =
    for {
      logger <- Slf4jLogger.create[F]

      prevTx <- prevTxPath match {
        case Some(path) =>
          readFromJsonFile(path)
            .handleErrorWith(e =>
              logger.error(e)(s"Error while reading previous transaction from path $path") >> MonadThrow[F]
                .raiseError[Option[Signed[Transaction]]](e)
            )
        case None => None.pure[F]
      }

      tx <- createDeployAppTransaction(
        keyPair,
        destination,
        appDataPath,
        appName,
        appVersion,
        appDescription,
        appDownloadURL,
        prevTx,
        amount,
        fee
      )

      _ <- writeToJsonFile(nextTxPath)(tx)
    } yield ()

  private def loadKeyPair[F[_]: Async: SecurityProvider](cfg: EnvConfig): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        cfg.keystore.toString,
        cfg.keyalias.coerce.value,
        cfg.storepass.coerce.value.toCharArray,
        cfg.keypass.coerce.value.toCharArray
      )
}
