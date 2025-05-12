package org.reality.combined

import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import org.reality.dag.l1.WasmExecutionRequest
import org.reality.dag.l1.modules.L1Queues
import org.reality.dag.transaction.TransactionValidator
import org.reality.modules.{AdditionalRoutes, HttpApi}
import org.reality.security.SecurityProvider
import org.reality.security.signature.SignedValidator
import org.reality.tools.HashUtils.hashJson

import io.circe.Json
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class WasmExecutorRoutes[F[_]: Async: SecurityProvider](nodeApi: HttpApi[F], transactionValidator: TransactionValidator[F])
    extends AdditionalRoutes[F]
    with Http4sDsl[F] {
  private val queues = nodeApi.queues.asInstanceOf[L1Queues[F]]
  private val wasmExecutionQueue = queues.wasmExecutionQueue
  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  val publicRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "execute-wasm" =>
      for {
        wasmRequest <- req.as[WasmExecutionRequest]
        _ <- logger.info(s"Received WASM execution request for function: ${wasmRequest.data.value.functionName}")

        signedValidator = SignedValidator.make[F]

        validatedTransaction <- signedValidator.validateSignatures(wasmRequest.transaction)
        validatedRequestData <- signedValidator.validateSignatures(wasmRequest.data)

        validationResult = (validatedTransaction, validatedRequestData).tupled

        response <- validationResult match {
          case Validated.Valid((validTransaction, validRequestData)) =>
            signedValidator.validateAllSignersMatch(List(validTransaction, validRequestData)) match {
              case Validated.Valid(_) =>
                for {
                  dataHash <- Async[F].delay(hashJson(validRequestData.value.params))
                  isValidHash = dataHash == validTransaction.value.dataHash
                  _ <-
                    if (isValidHash) Async[F].unit
                    else {
                      val errorMsg = "Data hash does not match the hash in the transaction"
                      logger.error(errorMsg) *>
                        BadRequest(Json.obj("error" -> Json.fromString(errorMsg)))
                    }

                  validatedTransactionResult <- transactionValidator.validate(validTransaction)

                  response <- validatedTransactionResult match {
                    case Validated.Valid(_) =>
                      for {
                        _ <- wasmExecutionQueue.offer(wasmRequest)
                        hashedTx <- validTransaction.toHashed[F]
                        response <- Ok(
                          Json.obj("dataHash" -> Json.fromString(s"${dataHash}"), "txHash" -> Json.fromString(s"${hashedTx.hash.value}"))
                        )
                      } yield response

                    case Validated.Invalid(errors) =>
                      val errorMsg = errors.toList.map(_.toString).mkString(", ")
                      logger.error(s"Transaction validation failed: $errorMsg") *>
                        BadRequest(Json.obj("error" -> Json.fromString(s"Transaction validation failed: $errorMsg")))
                  }
                } yield response

              case Validated.Invalid(errors) =>
                val errorMsg = errors.toList.map(_.toString).mkString(", ")
                logger.error(s"Signers do not match: $errorMsg") *>
                  BadRequest(Json.obj("error" -> Json.fromString(s"Signers do not match: $errorMsg")))
            }

          case Validated.Invalid(errors) =>
            val errorMsg = errors.toList.map(_.toString).mkString(", ")
            logger.error(s"Signature validation failed: $errorMsg") *>
              BadRequest(Json.obj("error" -> Json.fromString(s"Invalid signatures: $errorMsg")))
        }
      } yield response
  }

  val p2pRoutes: HttpRoutes[F] = HttpRoutes.empty[F]
}
