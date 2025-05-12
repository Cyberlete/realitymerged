package org.reality.dag.l1.http

import java.security.{KeyPair, MessageDigest}

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.kernel.Sync
import cats.effect.std.{Queue, Random, Supervisor}
import cats.implicits.catsSyntaxApplicativeError
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.io.Source

import org.reality.dag.l1.app.AppContext
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.reality.dag.l1.domain.transaction.{TransactionService, TransactionStorage, transactionLoggerName}
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.ext.crypto._
import org.reality.ext.http4s.{AddressVar, HashVar}
import org.reality.schema.http.{ErrorCause, ErrorResponse}
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction._
import org.reality.sdk.config.types.HttpConfig
import org.reality.sdk.domain.cluster.storage.L0ClusterStorage
import org.reality.sdk.http.p2p.clients.DeployAppTransactionInfo
import org.reality.security.SecurityProvider
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed

import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.shapes._
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class Routes[F[_]: Async: Random: SecurityProvider](
  transactionService: TransactionService[F],
  transactionStorage: TransactionStorage[F],
  l0ClusterStorage: L0ClusterStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]],
  p2pClient: L1P2PClient[F],
  appContext: AppContext[F],
  httpCfg: HttpConfig,
  selfId: PeerId,
  keyPair: KeyPair
)(implicit S: Supervisor[F])
    extends Http4sDsl[F] {

  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  transactionLogger.debug("Setting up L1 NET routes")

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "transactions" =>
      transactionLogger.info("Received a POST request to /transactions") >>
        req.as[Signed[Transaction]].attempt.flatMap {
          case Left(error) =>
            transactionLogger.error(s"Failed to parse the transaction: ${error.getMessage}") >>
              BadRequest(ErrorResponse(NonEmptyList.one(ErrorCause(s"Failed to parse the request body. ${error.getMessage}"))).asJson)
          case Right(transaction) =>
            transactionLogger.info(s"Parsed transaction from request: ${transaction.show}") >>
              (for {
                hashedTransaction <- transaction.toHashed[F]
                response <- transactionService
                  .offer(hashedTransaction)
                  .flatTap {
                    case Left(errors) =>
                      transactionLogger.warn(
                        s"Received transaction hash=${hashedTransaction.hash} is invalid: ${transaction.show}, reason: ${errors.show}"
                      )
                    case Right(hash) => transactionLogger.info(s"Received valid transaction: ${hash.show}")
                  }
                  .flatMap {
                    case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))).asJson)
                    case Right(hash)  => Ok(("hash" ->> hash.value) :: HNil)
                  }
              } yield response)
        }

    case GET -> Root / "transactions" / HashVar(hash) =>
      transactionStorage.find(hash).flatMap {
        case Some(tx) => Ok(TransactionView(tx.signed.value, tx.hash, TransactionStatus.Waiting).asJson)
        case None     => NotFound()
      }

    case GET -> Root / "transactions" / "last-reference" / AddressVar(address) =>
      transactionStorage
        .getLastAcceptedReference(address)
        .flatMap(Ok(_))

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))

    case req @ POST -> Root / "app-input" / appIdentifier =>
      implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]
      implicit val encoder: EntityEncoder[F, Array[Byte]] = EntityEncoder.byteArrayEncoder[F]

      (transactionLogger.info(s"Received a POST request to /app-input/$appIdentifier") >>
        req.as[Array[Byte]].attempt.flatMap {
          case Right(payload) =>
            transactionLogger.info(s"Payload received for address $appIdentifier: ${payload.mkString(" ")}")
            appContext.stateChannelProcessor.enqueueInput(appIdentifier, payload).attempt.flatMap {
              case Right(_) =>
                transactionLogger.info(s"Payload enqueued for address $appIdentifier")
                Ok(s"Input forwarded for address $appIdentifier")
              case Left(enqueueError) =>
                transactionLogger.error(enqueueError)(s"Error enqueuing payload for address $appIdentifier")
                InternalServerError()
            }
          case Left(parseError) =>
            transactionLogger.error(parseError)(s"Error parsing payload for address $appIdentifier")
            InternalServerError()
        }).handleErrorWith { ex =>
        transactionLogger.error(ex)("Unexpected error occurred")
        InternalServerError()
      }
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data" =>
      for {
        peerBlockConsensusInput <- req.as[Signed[PeerBlockConsensusInput]]
        _ <- S.supervise(peerBlockConsensusInputQueue.offer(peerBlockConsensusInput))
        response <- Ok()
      } yield response
  }

  def downloadFile(url: String)(implicit F: Sync[F]): F[Array[Byte]] = F.delay {
    Source.fromURL(url)("ISO-8859-1").map(_.toByte).toArray

  }
  def calculateHash(data: Array[Byte])(implicit F: Sync[F]): F[String] = F.delay {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data)
    md.digest().map("%02x".format(_)).mkString
  }

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "run-app" / appIdentifier =>
      transactionLogger.info(s"Received a GET request to /run-app/$appIdentifier") >>
        l0ClusterStorage.getRandomPeer.flatMap { p2pContext =>
          p2pClient.l0GlobalSnapshotClient.getApp(appIdentifier).run(p2pContext).attempt.flatMap {
            case Right(Some(DeployAppTransactionInfo(source, _, _, _, appDownloadURL, binaryHash))) =>
              (for {
                file <- downloadFile(appDownloadURL)
                calculatedHash <- calculateHash(file)
                _ <- if (calculatedHash == binaryHash) Applicative[F].unit else Sync[F].raiseError[Unit](new Exception("Hash mismatch"))
                _ <- transactionLogger.info(s"Hash check for $appIdentifier is successful")
                jar <- appContext.registry.createStateChannelJarWithAppIdentifier(file, appIdentifier)
                _ <- transactionLogger.info(s"created state channel under ${jar.id}")
                nodePublicAddress = keyPair.getPublic.toAddress
                amount = TransactionAmount(PosLong.MinValue)
                fee = TransactionFee(NonNegLong.MinValue)
                parent <- transactionStorage.getLastAcceptedReference(nodePublicAddress)
                salt <- Random[F].nextLong.map(TransactionSalt.apply)
                registerAppProviderTx = RegisterAppProviderTransaction(
                  nodePublicAddress,
                  source,
                  amount,
                  appIdentifier,
                  httpCfg.publicHttp.host.toString,
                  httpCfg.publicHttp.port.toString,
                  fee,
                  parent,
                  salt
                )

                signedTx <- registerAppProviderTx.sign(keyPair)
                hashedTransaction <- signedTx.toHashed[F]
                _ <- transactionService
                  .offer(hashedTransaction)

                _ <- transactionLogger
                  .info(
                    s"Sent app provider registration request transaction ${registerAppProviderTx}"
                  )
              } yield ()).attempt.flatMap {
                case Right(_) => Ok()
                case Left(ex) =>
                  transactionLogger.error(ex)(s"Failed in processing for appIdentifier $appIdentifier") >>
                    InternalServerError()
              }
            case Right(None) =>
              transactionLogger.info(s"No app found for identifier $appIdentifier") >>
                NotFound()
            case Left(ex) =>
              transactionLogger.error(ex)(s"Exception encountered while retrieving app with identifier $appIdentifier") >>
                InternalServerError()
          }
        }
  }

  val publicRoutes: HttpRoutes[F] = public

  val p2pRoutes: HttpRoutes[F] = p2p

  val cliRoutes: HttpRoutes[F] = cli
}
