package org.reality.tools

import java.io.File
import java.nio.file.{Path => JPath}
import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.std.{Console, Random}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.math.Integral.Implicits._

import org.reality.BuildInfo
import org.reality.infrastructure.genesis.types.GenesisCSVAccount
import org.reality.keytool.{KeyPairGenerator, KeyStoreUtils}
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops._
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelSnapshotBinary
import org.reality.tools.TransactionGenerator._
import org.reality.tools.cli.method._

import com.monovore.decline._
import com.monovore.decline.effect._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric._
import fs2._
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowEncoder
import fs2.io.file.{Files, Path}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.scalacheck.Gen.const

import TransactionCreator._

object Main
    extends CommandIOApp(
      name = "",
      header = "Reality Tools",
      version = BuildInfo.version
    ) {

  /** Continuously sends transactions to a cluster
    *
    * @example
    *   {{{ send-transactions localhost:9010
    * --loadWallets kubernetes/data/genesis-keys/ }}}
    *
    * @example
    *   {{{ send-transactions localhost:9010
    * --generateWallets 100
    * --genesisPath genesis.csv }}}
    *
    * Add --verbose flag for printTx logs
    *
    * @example
    *
    * {{{ send-deploy-app-transaction
    * --baseUrl localhost:9010
    * --appDownloadUrl localhost:8000
    * --appDataPath ./examples/empty/target/scala-2.13/empty_2.13-0.1.0-SNAPSHOT.jar
    * --destinationWalletPath ./kubernetes/data/genesis-keys/key-8.p12
    * --walletPath ./kubernetes/data/genesis-keys/key-9.p12 }}}
    */
  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        EmberClientBuilder.default[IO].build.use { client =>
          Random.scalaUtilRandom[IO].flatMap { implicit random =>
            (method match {
              case SendTransactionsCmd(basicOpts, walletsOpts, verbose) =>
                walletsOpts match {
                  case w: GeneratedWallets => sendTxsUsingGeneratedWallets(client, basicOpts, w, verbose)
                  case l: LoadedWallets    => sendTxsUsingLoadedWallets(client, basicOpts, l, verbose)
                }
              case SendStateChannelSnapshotCmd(baseUrl, verbose) =>
                sendStateChannelSnapshot(client, baseUrl, verbose)
              case SendStandardTransactionCmd(standardTransactionBasicOpts, standardTransactionWalletOpts) =>
                sendStandardTransaction(client, standardTransactionBasicOpts, standardTransactionWalletOpts)
              case SendDeployAppTransactionCmd(deployAppBasicOpts, deployAppTransactionWalletOpts) =>
                sendDeployAppTransaction(client, deployAppBasicOpts, deployAppTransactionWalletOpts)
              case _ => IO.raiseError(new Throwable("Not implemented"))
            }).as(ExitCode.Success)
          }
        }
      }
    }

  def sendDeployAppTransaction[F[_]: Async: Random: SecurityProvider: Console](
    client: Client[F],
    deployAppTransactionBasicOpts: SendDeployAppTransactionBasicOpts,
    deployAppTransactionWalletOpts: SendTransactionWalletOpts
  ): F[Unit] =
    for {
      alias <- Async[F].pure(deployAppTransactionWalletOpts.alias)
      password <- Async[F].pure(deployAppTransactionWalletOpts.password)
      sourceKey <- loadKey(Path.fromNioPath(deployAppTransactionWalletOpts.walletPath), alias, password)
      sourceLastTxRef <- getLastReference(client, deployAppTransactionBasicOpts.baseUrl)(sourceKey.getPublic.toAddress)
      sourceAddressParam = AddressParams(sourceKey, sourceLastTxRef)
      destinationKey <- loadKey(Path.fromNioPath(deployAppTransactionBasicOpts.destinationWalletPath), alias, password)
      destinationAddress = destinationKey.getPublic.toAddress
      signedSendDeployAppTransaction <- createSendDeployAppTransaction(
        deployAppTransactionBasicOpts,
        sourceAddressParam,
        destinationAddress
      )
      _ <- postTransaction(client, deployAppTransactionBasicOpts.baseUrl)(signedSendDeployAppTransaction)
      _ <- printTx[F](true)(signedSendDeployAppTransaction)
    } yield ()

  def sendStandardTransaction[F[_]: Async: Random: SecurityProvider: Console](
    client: Client[F],
    standardTransactionBasicOpts: SendStandardTransactionBasicOpts,
    standardTransactionWalletOpts: SendTransactionWalletOpts
  ): F[Unit] =
    for {
      alias <- Async[F].pure(standardTransactionWalletOpts.alias)
      password <- Async[F].pure(standardTransactionWalletOpts.password)
      sourceKey <- loadKey(Path.fromNioPath(standardTransactionWalletOpts.walletPath), alias, password)
      sourceLastTxRef <- getLastReference(client, standardTransactionBasicOpts.baseUrl)(sourceKey.getPublic.toAddress)
      sourceAddressParam = AddressParams(sourceKey, sourceLastTxRef)
      destinationKey <- loadKey(Path.fromNioPath(standardTransactionBasicOpts.destinationWalletPath), alias, password)
      destinationAddress = destinationKey.getPublic.toAddress
      signedStandardTransaction <- createSignedStandardTransaction(
        standardTransactionBasicOpts,
        sourceAddressParam,
        destinationAddress
      )
      _ <- postTransaction(client, standardTransactionBasicOpts.baseUrl)(signedStandardTransaction)
      _ <- printTx[F](verbose = true)(signedStandardTransaction)
    } yield ()

  def sendStateChannelSnapshot[F[_]: Async: SecurityProvider: Console](
    client: Client[F],
    baseUrl: UrlString,
    verbose: Boolean
  ): F[Unit] =
    for {
      key <- generateKeys(1).map(_.head)
      address = key.getPublic.toAddress
      _ <- console.green(s"Generated address: $address")
      snapshot = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
      signedSnapshot <- Signed.forAsyncJson(snapshot, key)
      hashed <- signedSnapshot.toHashed
      _ <- console.green(s"Snapshot hash: ${hashed.hash.show}, proofs hash: ${hashed.proofsHash.show}")
      _ <- postStateChannelSnapshot(client, baseUrl)(signedSnapshot, address)
    } yield ()

  def sendTxsUsingGeneratedWallets[F[_]: Async: Random: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: GeneratedWallets,
    verbose: Boolean
  ): F[Unit] =
    console.green(s"Configuration: ") >>
      console.yellow(s"Wallets: ${walletsOpts.count.show}") >>
      console.yellow(s"Genesis path: ${walletsOpts.genesisPath.toString.show}") >>
      generateKeys[F](PosInt.unsafeFrom(walletsOpts.count)).flatMap { keys =>
        createGenesis(walletsOpts.genesisPath, keys) >>
          console.cyan("Genesis created. Please start the network and continue... [ENTER]") >>
          Console[F].readLine >>
          sendTransactions(client, basicOpts, keys.map(AddressParams(_)))
            .flatMap(_ => Async[F].sleep(10.seconds))
            .flatMap(_ => checkLastReferences(client, basicOpts.baseUrl, keys.map(_.getPublic.toAddress)))
      }

  def sendTxsUsingLoadedWallets[F[_]: Async: Random: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: LoadedWallets,
    verbose: Boolean
  ): F[Unit] =
    for {
      keys <- loadKeys(walletsOpts).map(NonEmptyList.fromList).flatMap {
        Async[F].fromOption(_, new Throwable("Keys not found"))
      }
      _ <- console.green(s"Loaded ${keys.size} keys")
      addressParams <- keys.traverse { key =>
        getLastReference(client, basicOpts.baseUrl)(key.getPublic.toAddress)
          .map(lastTxRef => AddressParams(key, lastTxRef))
      }
      _ <- sendTransactions(client, basicOpts, addressParams)
      _ <- checkLastReferences(client, basicOpts.baseUrl, addressParams.map(_.address))
    } yield ()

  def sendTransactions[F[_]: Async: Random: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    addressParams: NonEmptyList[AddressParams]
  ): F[Unit] =
    Clock[F].monotonic.flatMap { startTime =>
      Ref.of(0L).flatMap { counterR =>
        val printProgressApplied = counterR.get.flatMap(printProgress(startTime, _))
        val progressPrinter = Stream
          .awakeEvery(1.seconds)
          .evalMap(_ => printProgressApplied)

        infiniteTransactionStream(basicOpts.chunkSize, basicOpts.fee, addressParams)
          .flatTap(tx =>
            Stream.retry(
              postTransaction(client, basicOpts.baseUrl)(tx)
                .handleErrorWith(e => console.red(e.show) >> e.raiseError[F, Unit]),
              0.5.seconds,
              d => (d * 1.25).asInstanceOf[FiniteDuration],
              basicOpts.retryAttempts
            )
          )
          .through(applyLimit(basicOpts.take))
          .through(applyDelay(basicOpts.delay))
          .evalTap(printTx[F](basicOpts.verbose))
          .evalMap(_ => counterR.update(_ |+| 1L))
          .handleErrorWith(e => Stream.eval(console.red(e.show)))
          .mergeHaltL(progressPrinter)
          .append(Stream.eval(printProgressApplied))
          .compile
          .drain
      }
    }

  def applyLimit[F[_], A](maybeLimit: Option[PosLong]): Pipe[F, A, A] =
    in => maybeLimit.map(in.take(_)).getOrElse(in)

  def applyDelay[F[_]: Temporal, A](delay: Option[FiniteDuration]): Pipe[F, A, A] =
    in => delay.map(in.spaced(_)).getOrElse(in)

  def loadKey[F[_]: Async: SecurityProvider: Console](path: Path, alias: String, password: String): F[KeyPair] =
    console.cyan(s"Loading key from path: $path") *>
      Async[F]
        .attempt(
          KeyStoreUtils.readKeyPairFromStore(
            path.toString,
            alias,
            password.toCharArray,
            password.toCharArray
          )
        )
        .flatMap {
          case Left(e) =>
            val workingDir = new File(".").getAbsolutePath
            val filesInDir = new File(workingDir).listFiles().toList
            val fileList = filesInDir.map(_.getName).mkString(", ")
            console.red(s"Error loading key from path: $path in working directory: $workingDir. Error: ${e.getMessage}") *>
              console.yellow(s"Files in directory: $fileList") *>
              Async[F].raiseError(e)
          case Right(keyPair) =>
            Async[F].pure(keyPair)
        }

  def loadKeys[F[_]: Files: Async: SecurityProvider](opts: LoadedWallets): F[List[KeyPair]] =
    Files[F]
      .walk(Path.fromNioPath(opts.walletsPath), 1, followLinks = false)
      .filter(_.extName === ".p12")
      .evalMap { keyFile =>
        KeyStoreUtils.readKeyPairFromStore(
          keyFile.toString,
          opts.alias,
          opts.password.toCharArray,
          opts.password.toCharArray
        )
      }
      .compile
      .toList

  def generateKeys[F[_]: Async: SecurityProvider](wallets: PosInt): F[NonEmptyList[KeyPair]] =
    NonEmptyList.fromListUnsafe((1 to wallets).toList).traverse { _ =>
      KeyPairGenerator.makeKeyPair[F]
    }

  def createGenesis[F[_]: Async](genesisPath: JPath, keys: NonEmptyList[KeyPair]): F[Unit] = {
    implicit val encoder: RowEncoder[GenesisCSVAccount] = deriveRowEncoder

    Stream
      .emits[F, KeyPair](keys.toList)
      .map(_.getPublic.toAddress)
      .map(_.value.toString)
      .map(GenesisCSVAccount(_, 100000000L))
      .through(encodeWithoutHeaders[GenesisCSVAccount]())
      .through(text.utf8.encode)
      .through(Files[F].writeAll(Path.fromNioPath(genesisPath)))
      .compile
      .drain
  }

  def printProgress[F[_]: Async: Console](startTime: FiniteDuration, counter: Long): F[Unit] =
    Clock[F].monotonic.flatMap { currentTime =>
      val (minutes, seconds) = (currentTime - startTime).toSeconds /% 60
      console.green(s"$counter transactions sent in ${minutes}m ${seconds}s")
    }

  def printTx[F[_]: Applicative: Console](verbose: Boolean)(tx: Signed[Transaction]): F[Unit] =
    Applicative[F].whenA(verbose) {
      val transactionInfo = tx.value match {
        case registerAppProviderTransaction: RegisterAppProviderTransaction =>
          s"REGISTER_APP_PROVIDER appIdentifier=${registerAppProviderTransaction.appIdentifier} host=${registerAppProviderTransaction.host} port=${registerAppProviderTransaction.port}"
        case deployTx: DeployAppTransaction =>
          val appDataLog = s"appName=${deployTx.appName} appVersion=${deployTx.appVersion}"
          s"DEPLOY_APP destination=${deployTx.destination} ${appDataLog} fee=${deployTx.fee} parent=${deployTx.parent}"
        case standardTx: StandardTransaction =>
          s"STANDARD destination=${standardTx.destination} amount=${standardTx.amount} fee=${standardTx.fee} parent=${standardTx.parent}"
        case recordDataTransaction: RecordDataTransaction =>
          s"RECORD_DATA destination=${recordDataTransaction.destination} dataHash=${recordDataTransaction.dataHash} fee=${recordDataTransaction.fee} parent=${recordDataTransaction.parent}"
        case swapTx: SwapTx =>
          s"RECORD_DATA destination=${swapTx.destination} destination=${swapTx.destination} fee=${swapTx.fee} parent=${swapTx.parent}"

      }

      console.cyan(
        s"Transaction sent ordinal=${tx.ordinal} sourceAddress=${tx.source} ${transactionInfo}"
      )
    }

  def postStateChannelSnapshot[F[_]: Async](
    client: Client[F],
    baseUrl: UrlString
  )(snapshot: Signed[StateChannelSnapshotBinary], address: Address): F[Unit] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath(s"state-channels/${address.value.value}/snapshot")
    val req = Request[F](method = Method.POST, uri = target).withEntity(snapshot)

    client.successful(req).void
  }

  def postTransaction[F[_]: Async: Console](client: Client[F], baseUrl: UrlString)(
    tx: Signed[Transaction]
  ): F[Unit] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath("transactions")
    val req = Request[F](method = Method.POST, uri = target).withEntity(tx)

    Console[F].println(s"Posting to URL: ${target.toString}").flatMap(_ => client.successful(req).void)
  }

  def checkLastReferences[F[_]: Async: Console](
    client: Client[F],
    baseUrl: UrlString,
    addresses: NonEmptyList[Address]
  ): F[Unit] =
    addresses.traverse(checkLastReference(client, baseUrl)).void

  def checkLastReference[F[_]: Async: Console](client: Client[F], baseUrl: UrlString)(address: Address): F[Unit] =
    getLastReference(client, baseUrl)(address).flatMap { reference =>
      console.green(s"Reference for address: ${address} is ${reference.show}")
    }

  def getLastReference[F[_]: Async](client: Client[F], baseUrl: UrlString)(
    address: Address
  ): F[TransactionReference] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath(s"transactions/last-reference/${address.value.value}")
    val req = Request[F](method = Method.GET, uri = target)

    client.expect[TransactionReference](req)
  }

  object console {
    def red[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.RED}${t}${scala.Console.RESET}")
    def cyan[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.CYAN}${t}${scala.Console.RESET}")
    def green[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.GREEN}${t}${scala.Console.RESET}")
    def yellow[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.YELLOW}${t}${scala.Console.RESET}")
  }
}
