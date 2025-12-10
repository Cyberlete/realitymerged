import cats.effect.std.Random
import cats.effect.unsafe.IORuntime
import cats.effect.{Async, IO, Temporal}
import cats.syntax.all.*
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.circe.Json
import io.circe.syntax.*
import org.bouncycastle.crypto.digests.SHA256Digest
import org.reality.combined.{ProofGenerationError, RealZKWasmExecutor, ZKProofError}
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.WasmOutputWrapper
import org.reality.dag.l1.domain.consensus.block.*
import org.reality.ext.crypto.*
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel.*
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount
import org.reality.schema.netAddress.NETAddress
import org.reality.schema.transaction.*
import org.reality.security.SecurityProvider
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.circe._
import org.typelevel.ci.CIString

import java.net.{ConnectException, SocketTimeoutException}
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Future
import scala.concurrent.duration.*
object CyberleteWasmExecutorCellObj extends StateChannelCell {
  implicit val runtime: IORuntime = cats.effect.unsafe.implicits.global

  // Extension method to convert Future to F[_]
  implicit class FutureOps[A](val future: Future[A]) {
    def toAsync[F[_]](implicit F: Async[F]): F[A] =
      F.fromFuture(F.delay(future))
  }

  // ZK executor for proof verification (shared instance)
  // Note: RealZKWasmExecutor is thread-safe for proof generation/verification
  private val zkWasmExecutor = new RealZKWasmExecutor[IO]

  // Timeout for proof operations to prevent blocking indefinitely
  private val ProofOperationTimeout = 30.seconds

  /**
   * Lift an IO operation to F[_] without blocking
   * This properly converts IO to any Async[F] without using unsafeRunSync
   */
  private def liftIO[F[_]: Async, A](io: IO[A]): F[A] =
    Async[F].async_ { cb =>
      io.unsafeRunAsync {
        case Right(a) => cb(Right(a))
        case Left(e)  => cb(Left(e))
      }(runtime)
    }

  // Validator API configuration
  private val API_ENDPOINT = System.getProperty("validator.api.endpoint", "http://161.35.184.143:5001/api/movement-analysis")
  private val CONNECTION_TIMEOUT = 5.seconds
  private val SKIP_API_CALLS = API_ENDPOINT.toLowerCase == "none" || System.getProperty("validator.skip.api", "false").toBoolean

  // Compute SHA256 hash of proof file content
  private def computeProofHash(proofPath: String): String = {
    val digest = new SHA256Digest()
    val proofContent = Files.readAllBytes(Paths.get(proofPath))
    digest.update(proofContent, 0, proofContent.length)
    val hashBytes = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hashBytes, 0)
    hashBytes.map("%02x".format(_)).mkString
  }


  def mkCell[F[_]: Async: SecurityProvider: Random](ctx: BlockConsensusContext[F]): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell[F](ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = mkCellCyber[F](ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
  }

  def mkCellCyber[F[_]: Async: SecurityProvider](ctx: BlockConsensusContext[F]): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = {
    val zkExecutor = zkWasmExecutor

    // Helper to send data to validator API
    def sendToValidatorApi(analysisData: Json): F[Unit] =
      if (SKIP_API_CALLS) {
        Async[F].delay(println(s"[STARK] API calls disabled - skipping validation API call"))
      } else {
        Uri.fromString(API_ENDPOINT) match {
          case Left(parseError) =>
            Async[F].delay(println(s"[STARK] Invalid API endpoint URI: $parseError"))
          case Right(uri) =>
            val request = Request[F](Method.POST, uri)
              .withEntity(analysisData)
              .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

            EmberClientBuilder.default[F].build.use { client =>
              for {
                startTime <- Async[F].delay(System.currentTimeMillis())
                response <- client.run(request).use { response =>
                  for {
                    body <- response.as[String]
                    endTime <- Async[F].delay(System.currentTimeMillis())
                    _ <- Async[F].delay {
                      println(s"[STARK] Validator response in ${endTime - startTime}ms, status: ${response.status.code}")
                      if (response.status.isSuccess) {
                        println(s"[STARK] Successfully sent STARK proof data to validator")
                      } else {
                        println(s"[STARK] Failed to send data: ${response.status.reason}")
                      }
                    }
                  } yield ()
                }
              } yield ()
            }.handleErrorWith { error =>
              Async[F].delay {
                error match {
                  case e: ConnectException =>
                    println(s"[STARK] CONNECTION ERROR: ${e.getMessage}")
                    println(s"[STARK] Continuing without validation API")
                  case e: SocketTimeoutException =>
                    println(s"[STARK] TIMEOUT ERROR (${CONNECTION_TIMEOUT}): ${e.getMessage}")
                  case e: Throwable =>
                    println(s"[STARK] UNEXPECTED ERROR: ${e.getClass.getName}: ${e.getMessage}")
                }
              }
            }
        }
      }

    data => {
      new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
        data,
        scheme.hyloM(
          AlgebraM[F, StackF, Either[CellError, Ω]] {
            case More(a) => Async[F].pure(a)
            case Done(Right(cmd: AlgebraCommand)) =>
              cmd match {
                case AlgebraCommand.ProcessWasmOutput(wrapper) =>
                  for {
                    _ <- Async[F].delay(println("[STARK] Starting STARK proof generation for WASM output"))

                    // event extraction with logging
                    events = extractEvents(wrapper.output.request.data.asJson)
                    eventCount = events.size
                    _ <- Async[F].delay(println(s"[STARK] Extracted $eventCount gaming input events for STARK proof"))

                    privyId = wrapper.output.request.data.asJson.hcursor
                      .downField("value")
                      .downField("privyId")
                      .focus
                      .flatMap(_.asString)
                      .orElse(
                        wrapper.output.request.data.asJson.hcursor
                          .downField("value")
                          .downField("params")
                          .downField("privyId")
                          .focus
                          .flatMap(_.asString)
                      )

                    gameId = wrapper.output.request.data.asJson.hcursor
                      .downField("value")
                      .downField("gameId")
                      .focus
                      .flatMap(_.asString)
                      .orElse(
                        wrapper.output.request.data.asJson.hcursor
                          .downField("value")
                          .downField("params")
                          .downField("gameId")
                          .focus
                          .flatMap(_.asString)
                      )
                      .getOrElse("detected_game")

                    userId = privyId.getOrElse(wrapper.output.request.data.proofs.head.id.hex.toString)

                    _ <- Async[F].delay(println(s"[STARK] Processing STARK proof for user ID: $userId (Game: $gameId)"))

                    // Prepare analysis data for validator API - enhanced for crypto proofs
                    analysisData <- Async[F].delay(
                      Json.obj(
                        "analysisData" -> Json.obj(
                          "zkProofType" -> Json.fromString("STARK_PROOF"),
                          "securityLevel" -> Json.fromString("PRODUCTION_GRADE"),
                          "wasmAnalysis" -> wrapper.output.asJson.hcursor
                            .downField("result")
                            .focus
                            .getOrElse(Json.obj()),
                          "transaction" -> Json.obj(
                            "status" -> Json.fromString("completed"),
                            "txHash" -> Json.fromString(wrapper.output.request.transaction.salt.toString),
                            "timestamp" -> Json.fromLong(System.currentTimeMillis()),
                            "dataHash" -> Json.fromString(wrapper.output.request.data.proofs.head.id.hex.toString)
                          ),
                          "metadata" -> Json.obj(
                            "userId" -> Json.fromString(userId),
                            "sessionId" -> Json.fromString(wrapper.output.request.transaction.salt.toString),
                            "gameId" -> Json.fromString(gameId),
                            "timestamp" -> Json.fromLong(System.currentTimeMillis()),
                            "privyId" -> Json.fromString(privyId.getOrElse(""))
                          ),
                          "rawEvents" -> Json.obj(
                            "count" -> Json.fromInt(eventCount),
                            "dataType" -> Json.fromString("mouse_keyboard_gaming_input")
                          )
                        ),
                        "events" -> Json.fromValues(events)
                      )
                    )

                    // Send data to validator API (non-blocking)
                    _ <- sendToValidatorApi(analysisData)

                    // inputs that will be generated during proof creation
                    publicInputs = List(
                      ("input_0", BigInt(0)), // First wasmParam: Val.fromI32(0)
                      ("input_1", BigInt(eventCount)) // Second wasmParam: Val.fromI32(eventCount)
                    )

                    // logging message for proof
                    _ <- Async[F].delay(
                      println(
                        s"[STARK] Generating STARK proof for $eventCount events with inputs: $publicInputs"
                      )
                    )

                    // Generate STARK proof - using proper async execution instead of blocking unsafeRunSync
                    // This prevents thread starvation and allows proper fiber-based concurrency
                    proofResult <- liftIO(
                      zkExecutor
                        .generateProof(
                          s"gaming_input_${System.currentTimeMillis()}",
                          publicInputs,
                          List.empty
                        )
                    ).handleErrorWith { e =>
                      Async[F].delay {
                        println(s"[STARK] Proof generation error: ${e.getMessage}")
                        Left(ProofGenerationError(e.getMessage)): Either[ZKProofError, Path]
                      }
                    }

                    _ <- Async[F].delay(println(s"[STARK] Proof generation result: $proofResult"))

                    // Process STARK proof result and create transaction with hash
                    result <- proofResult match {
                      case Right(proofPath) =>
                        for {
                          _ <- Async[F].delay(println(s"[STARK] Proof generated at: $proofPath"))

                          // Verify the STARK proof - using proper async execution
                          verificationResult <- liftIO(
                            zkWasmExecutor.verifyProof(proofPath, publicInputs)
                          ).map {
                            case Right(isValid) =>
                              println(s"[STARK] Proof verification result: $isValid")
                              isValid
                            case Left(error) =>
                              println(s"[STARK] Verification error: ${error.message}")
                              false
                          }.handleErrorWith { e =>
                            Async[F].delay {
                              println(s"[STARK] Verification exception: ${e.getMessage}")
                              false
                            }
                          }

                          // Compute proof hash and create transaction
                          finalResult <- if (verificationResult) {
                            for {
                              proofHash <- Async[F].delay(computeProofHash(proofPath.toString))
                              _ <- Async[F].delay(println(s"[STARK] Proof hash (SHA-256): $proofHash"))

                              address <- ctx.selfId.toAddress
                              destination = Address(NETAddress("NET3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS"))

                              // Create transaction with STARK proof hash in binaryHash field
                              rAppTx = RAppStarkHashTransaction(
                                source = address,
                                destination = destination,
                                binaryHash = proofHash,
                                starkProof = "",
                                fee = TransactionFee.zero,
                                amount = TransactionAmount(Amount(0L)),
                                parent = TransactionReference.empty,
                                salt = TransactionSalt(System.currentTimeMillis())
                              )

                              signedRAppTx <- rAppTx.sign(ctx.keyPair)
                              hashedSignedRAppTx <- signedRAppTx.toHashed
                              validationResult <- ctx.transactionValidator.validate(signedRAppTx)
                              _ <- ctx.transactionStorage.put(hashedSignedRAppTx)
                              _ <- Async[F].delay {
                                println(s"[STARK] Transaction validation: $validationResult")
                                println(s"[STARK] STARK proof hash recorded on-chain: $proofHash")
                              }
                            } yield Right(NullTerminal): Either[CellError, Ω]
                          } else {
                            Async[F].delay(println("[STARK] Proof verification failed - not recording on-chain")) *>
                              Async[F].pure(Right(NullTerminal): Either[CellError, Ω])
                          }
                        } yield finalResult

                      case Left(error: ZKProofError) =>
                        Async[F].delay(println(s"[STARK] Proof generation failed: ${error.message}")) *>
                          Async[F].pure(Right(NullTerminal): Either[CellError, Ω])
                    }
                  } yield result

                case _ =>
                  Async[F].pure(Right(NullTerminal): Either[CellError, Ω])
              }
            case Done(other) =>
              other match {
                case Right(_) =>
                  Async[F].pure(Right(NullTerminal): Either[CellError, Ω])
                case Left(error) =>
                  Async[F].delay(println(s"[STARK] Error: ${error.toString}")) *>
                    Async[F].pure(Left(error))
              }
          },
          CoalgebraM[F, StackF, Ω] {
            case CoalgebraCommand.EnqueueWasmOutput(wrapper) =>
              Async[F].pure(Done(Right(AlgebraCommand.ProcessWasmOutput(wrapper)): Either[CellError, AlgebraCommand]))
            case foo =>
              println(s"recieved $foo in Cyberlete Cell Obj")

              Async[F].pure(Done(Right(AlgebraCommand.NoAction): Either[CellError, AlgebraCommand]))
          }
        ),
        {
          case d: WasmOutputWrapper => CoalgebraCommand.EnqueueWasmOutput(d)//todo instead of WasmOutputWrapper match on rAppStarkTransaction?
          case bar                   =>
            println(s"recieved $bar in Cyberlete Cell Obj")

            CoalgebraCommand.Empty()
        }
      )
    }
  }

  // Extract gaming input events from JSON
  private def extractEvents(json: Json): Vector[Json] =
    try {
      val events = json.hcursor
        .downField("value")
        .downField("params")
        .downField("events")
        .focus
        .flatMap(_.asArray)
        .getOrElse(Vector.empty)

      println(s"[STARK] Extracted ${events.size} gaming input events")
      events
    } catch {
      case e: Exception =>
        println(s"[STARK] Error extracting events: ${e.getMessage}")
        Vector.empty
    }
}
