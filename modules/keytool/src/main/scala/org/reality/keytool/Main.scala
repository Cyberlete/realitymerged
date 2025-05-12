package org.reality.keytool

import java.security.KeyStore

import cats.effect.{Async, ExitCode, IO}

import org.reality.BuildInfo
import org.reality.cli.env._
import org.reality.keytool.cert.DistinguishedName
import org.reality.keytool.cli.method._
import org.reality.security.SecurityProvider
import org.reality.security.hex.Hex

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import io.estatico.newtype.ops._
import org.bitcoinj.wallet.DeterministicSeed
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main
    extends CommandIOApp(
      name = "",
      header = "Reality Keytool",
      version = BuildInfo.version
    ) {
  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        method match {
          case GenerateMnemonic(keyStore, alias, passphrase, distinguishedName, certificateValidityDays) =>
            generateMnemonic[IO](keyStore, alias, passphrase, distinguishedName, certificateValidityDays).void
              .handleErrorWith(err => logger.error(err)(s"Error while generating mnemonic."))
              .as(ExitCode.Success)
          case GenerateWallet(keyStore, alias, password, distinguishedName, certificateValidityDays) =>
            generateKeyStoreWithKeyPair[IO](keyStore, alias, password, distinguishedName, certificateValidityDays).void
              .handleErrorWith(err => logger.error(err)(s"Error while generating a keystore."))
              .as(ExitCode.Success)
          case MigrateExistingKeyStoreToStorePassOnly(
                keyStore,
                alias,
                storepass,
                keypass,
                distinguishedName,
                certificateValidityDays
              ) =>
            migrateKeyStoreToSinglePassword[IO](
              keyStore,
              alias,
              storepass,
              keypass,
              distinguishedName,
              certificateValidityDays
            ).void
              .handleErrorWith(err => logger.error(err)(s"Error while migrating the keystore."))
              .as(ExitCode.Success)
          case ExportPrivateKeyHex(keyStore, alias, storepass, keypass) =>
            exportPrivateKeyAsHex[IO](keyStore, alias, storepass, keypass).void
              .handleErrorWith(err => logger.error(err)(s"Error while exporting private key as hex."))
              .as(ExitCode.Success)
          case ExportPeerId(keyStore, alias, storepass, keypass) =>
            exportPeerId[IO](keyStore, alias, storepass, keypass).void
              .handleErrorWith(err => logger.error(err)(s"Error while exporting peerId as hex."))
              .as(ExitCode.Success)
        }
      }
    }

  private def generateMnemonic[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    passphrase: Passphrase,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[DeterministicSeed] =
    KeyStoreUtils.generateMnemonic(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      passphrase = passphrase.coerce.toString,
      distinguishedName = distinguishedName,
      certificateValidityDays = certificateValidityDays
    )

  private def generateKeyStoreWithKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    KeyStoreUtils
      .generateKeyPairToStore(
        path = keyStore.coerce.toString,
        alias = alias.coerce.value,
        password = password.coerce.value.toCharArray,
        distinguishedName = distinguishedName,
        certificateValidityDays = certificateValidityDays
      )

  private def migrateKeyStoreToSinglePassword[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    storePass: StorePass,
    keyPass: KeyPass,
    distinguishedName: DistinguishedName,
    certificateValidityDays: Long
  ): F[KeyStore] =
    KeyStoreUtils.migrateKeyStoreToSinglePassword(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      storePassword = storePass.coerce.value.toCharArray,
      keyPassword = keyPass.coerce.value.toCharArray,
      distinguishedName = distinguishedName,
      certificateValidityDays = certificateValidityDays
    )

  private def exportPrivateKeyAsHex[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    storePass: StorePass,
    keyPass: KeyPass
  ): F[Hex] =
    KeyStoreUtils.exportPrivateKeyAsHex(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      storePassword = storePass.coerce.value.toCharArray,
      keyPassword = keyPass.coerce.value.toCharArray
    )

  private def exportPeerId[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    storePass: StorePass,
    keyPass: KeyPass
  ): F[Hex] =
    KeyStoreUtils.exportPeerId(
      path = keyStore.coerce.toString,
      alias = alias.coerce.value,
      storePassword = storePass.coerce.value.toCharArray,
      keyPassword = keyPass.coerce.value.toCharArray
    )
}
