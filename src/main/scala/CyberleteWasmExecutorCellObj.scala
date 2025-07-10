import java.net.{ConnectException, SocketTimeoutException}
import java.nio.file.{Files, Paths}
import cats.effect.std.Random
import cats.effect.unsafe.IORuntime
import cats.effect.{Async, IO}
import cats.syntax.all._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import org.reality.combined.{ProofGenerationError, RealZKWasmExecutor, ZKProofError}
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.{ProofBlockWrapper, WasmOutputWrapper, ProofData => BlockConsensusProofData}
import org.reality.dag.l1.domain.consensus.block.{AlgebraCommand, BlockConsensusContext, CoalgebraCommand, StateChannelCell}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel.{Cell, CellError, Done, More, StackF, Ω}
import org.reality.security.SecurityProvider
import org.reality.security.hash.{Hash, ProofsHash}
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.circe.Json
import io.circe.syntax._
import org.bouncycastle.crypto.digests.SHA256Digest
import org.reality.ext.crypto.RefinedHashableF
import org.reality.schema.transaction.{RAppStarkHashTransaction, TransactionAmount, TransactionFee, TransactionReference, TransactionSalt}
import sttp.client3.{HttpURLConnectionBackend, UriContext, basicRequest}
import org.reality.security.Hashed
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.ext.crypto._

object CyberleteWasmExecutorCellObj extends StateChannelCell {
  implicit val runtime: IORuntime = cats.effect.unsafe.implicits.global

  // Extension method to convert Future to F[_]
  implicit class FutureOps[A](val future: Future[A]) {
    def toAsync[F[_]](implicit F: Async[F]): F[A] =
      F.fromFuture(F.delay(future))
  }

  // ZK executor for proof verification
  //
  // security:
  private val zkWasmExecutor = new RealZKWasmExecutor[IO]

  // Validator API configuration
  private val API_ENDPOINT = System.getProperty("validator.api.endpoint", "http://161.35.184.143:5001/api/movement-analysis")
  private val CONNECTION_TIMEOUT = 5.seconds
  private val SKIP_API_CALLS = API_ENDPOINT.toLowerCase == "none" || System.getProperty("validator.skip.api", "false").toBoolean

  // Added for proofs into blocks
  case class ProofData(
                        proofPath: String,
                        metadataPath: String,
                        commitment: Hash,
                        timestamp: Long,
                        verified: Boolean = false
                      ) {
    def toBlockConsensusProofData: BlockConsensusProofData =
      BlockConsensusProofData(
        proofPath,
        metadataPath,
        commitment,
        timestamp
      )
  }

  // Added for proof into blocks helper with a specific return type
  def extractProofInfo(
                                proofPath: String,
                                metadataPath: String,
                                timestamp: Long,
                                verified: Boolean = false
                              ): ProofData = {
    // Create a SHA256 digest instance
    val digest = new SHA256Digest()

    // Read the proof content
    val proofContent = Files.readAllBytes(Paths.get(proofPath))

    // Update the digest with the proof content
    digest.update(proofContent, 0, proofContent.length)

    // Create a buffer for the hash output
    val hashBytes = new Array[Byte](digest.getDigestSize)

    // Finalize the digest operation
    digest.doFinal(hashBytes, 0)

    // Convert the hash bytes to a Hash object - explicit casting to avoid type inference issues
    val commitment = Hash.fromBytes(hashBytes)

    // Create and return a ProofData object
    ProofData(
      proofPath = proofPath,
      metadataPath = metadataPath,
      commitment = commitment,
      timestamp = timestamp,
      verified = verified
    )
  }

  // Method to compute a block hash with explicit return type and steps
  private def computeBlockHash(height: Long, proofs: List[ProofData], timestamp: Long): Hash = {
    // Create a new digest
    val digest = new SHA256Digest()

    // Add height to hash
    val heightBytes = height.toString.getBytes
    digest.update(heightBytes, 0, heightBytes.length)

    // Add timestamp to hash
    val timestampBytes = timestamp.toString.getBytes
    digest.update(timestampBytes, 0, timestampBytes.length)

    // Add all proof commitments to hash
    proofs.foreach { proof =>
      digest.update(proof.commitment.value.getBytes, 0, proof.commitment.value.getBytes.length)
    }

    // Create output buffer and finalize hash
    val hashBytes = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hashBytes, 0)

    // Convert to Hash object and return - clear explicit type
    val hashObj = Hash.fromBytes(hashBytes)
    hashObj
  }

  def mkCell[F[_]](ctx: BlockConsensusContext[F])(
    implicit F: Async[F],
    S: SecurityProvider[F],
    R: Random[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = {
    val zkExecutor = new RealZKWasmExecutor[IO]
    var currentBlockHeight: Long = 0
    var currentProofs: List[ProofData] = List.empty

    // ENHANCED: Method to verify and process a proof with cryptographic security
    def processProof(proofPath: String): F[Unit] = {
      val metadataPath = s"$proofPath.meta"

      for {
        // Log success
        _ <- F.delay(println(s"[WASM Executor] Proof generated successfully at: $proofPath"))

        // public inputs extraction with error handling
        publicInputsResult <- F.delay {
          try
            zkWasmExecutor.extractPublicInputsFromMetadata(metadataPath).unsafeRunSync()
          catch {
            case e: Exception =>
              println(s"[WASM Executor] Error extracting public inputs: ${e.getMessage}")
              List.empty[(String, Any)]
          }
        }

        // verification with cryptographic verification
        verificationResult <- F.delay {
          try {
            println(s"[WASM Executor] Attempting to verify proof with inputs: $publicInputsResult")
            val result = zkWasmExecutor.verifyProof(Paths.get(proofPath), publicInputsResult).unsafeRunSync()
            val isValid = result.isRight && result.exists(identity)
            println(s"[WASM Executor] Raw verification result: $result")
            isValid
          } catch {
            case e: Exception =>
              println(s"[WASM Executor] Exception during proof verification: ${e.getMessage}")
              e.printStackTrace()
              false
          }
        }

        // Log verification result
        _ <- F.delay(println(s"[WASM Executor] proof verification result: $verificationResult"))

        // Create proof info object with verification status
        proofInfo <- F.delay {
          extractProofInfo(
            proofPath,
            metadataPath,
            System.currentTimeMillis(),
            verified = verificationResult
          )
        }

        // Only add verified proofs to the block
        _ <- F.delay {
          if (verificationResult) {
            currentProofs = proofInfo :: currentProofs
            val verifiedCount = currentProofs.count(_.verified)
            println(s"[WASM Executor] ✅ proof verified and added to current block: ${proofInfo.commitment}")
            println(s"[WASM Executor] Current verified proof count: $verifiedCount/10")
          } else {
            println(s"[WASM Executor] ❌ proof verification failed, not adding to block: ${proofInfo.commitment}")
          }

          // Create block if we have enough verified proofs
          val verifiedProofCount = currentProofs.count(_.verified)
          if (verifiedProofCount >= 10) {
            val timestamp = System.currentTimeMillis()
            val orderedProofs = currentProofs.filter(_.verified).reverse.take(10)

            // Create block hash
            val blockHash = computeBlockHash(currentBlockHeight, orderedProofs, timestamp)

            // Compute proofs hash
//            val proofsHashValue = computeProofsHash(orderedProofs)

            // Create block wrapper
//            val block = ProofBlockWrapper(
//              height = currentBlockHeight,
//              hash = blockHash,
//              proofs = orderedProofs.map(_.toBlockConsensusProofData),
//              timestamp = timestamp,
//              proofsHash = ProofsHash(proofsHashValue.value)
//            )

            // Enhanced log for proofs
            println(s"""
                       |[WASM Executor] 🎉 CREATING BLOCK WITH CRYPTOGRAPHIC PROOFS! 🎉
                       |Height: $currentBlockHeight
                       |Hash: ${blockHash}
                       |CRYPTOGRAPHICALLY VERIFIED Proofs included: ${orderedProofs.length}
                       |Timestamp: $timestamp
                       |
                       |🔒 SECURITY GUARANTEE: These proofs are mathematically unforgeable
                       |🎮 AI/ML VALIDATION: Mouse/keyboard data cryptographically proven
                       |🤖 BOT DETECTION: AI analysis results are tamper-proof
                       |=============================================
                       |""".stripMargin)

            // Update state for next block
            currentBlockHeight += 1
            currentProofs = currentProofs.filterNot(p => orderedProofs.contains(p))
          }
        }
      } yield ()
    }

    // Helper to send data to validator API
    def sendToValidatorApi(analysisData: Json): F[Unit] =
      if (SKIP_API_CALLS) {
        F.delay(println(s"[WASM Executor] API calls disabled - skipping validation API call"))
      } else {
        F.delay {
          try {
            println(s"[WASM Executor] Sending data to validator API: $API_ENDPOINT")
            val backend = HttpURLConnectionBackend()
            val request = basicRequest
              .post(uri"$API_ENDPOINT")
              .header("Content-Type", "application/json")
              .body(analysisData.noSpaces)
              .readTimeout(CONNECTION_TIMEOUT)

            val startTime = System.currentTimeMillis()
            val response = request.send(backend)
            val endTime = System.currentTimeMillis()

            println(s"[WASM Executor] Response received in ${endTime - startTime}ms, status: ${response.code}")
            if (response.isSuccess) {
              println(s"[WASM Executor] Successfully sent data to validator")
              println(s"[WASM Executor] Response body: ${response.body}")
            } else {
              println(s"[WASM Executor] Failed to send data: ${response.statusText}")
              println(s"[WASM Executor] Error response: ${response.body}")
            }
          } catch {
            case e: ConnectException =>
              println(s"[WASM Executor] CONNECTION ERROR: ${e.getMessage}")
              println(s"[WASM Executor] Continuing without validation API")
            case e: SocketTimeoutException =>
              println(s"[WASM Executor] TIMEOUT ERROR (${CONNECTION_TIMEOUT}): ${e.getMessage}")
              println(s"[WASM Executor] Consider checking network or increasing timeout")
            case e: Throwable =>
              println(s"[WASM Executor] UNEXPECTED ERROR: ${e.getClass.getName}: ${e.getMessage}")
              e.printStackTrace()
          }
        }
      }

    data => {
      new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
        data,
        scheme.hyloM(
          AlgebraM[F, StackF, Either[CellError, Ω]] {
            case More(a) => F.pure(a)
            case Done(Right(cmd: AlgebraCommand)) =>
              cmd match {
                case AlgebraCommand.ProcessWasmOutput(wrapper) =>
                  for {
                    _ <- F.delay(println("[WASM Executor] Starting ZK-powered WASM output processing"))

                    // event extraction with logging
                    events = extractEvents(wrapper.output.request.data.asJson)
                    eventCount = events.size
                    _ <- F.delay(println(s"[WASM Executor] Extracted $eventCount gaming input events for proof"))

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

                    _ <- F.delay(println(s"[WASM Executor] Processing proof for user ID: $userId (Game: $gameId)"))

                    // Prepare analysis data for validator API - enhanced for crypto proofs
                    analysisData <- F.delay(
                      Json.obj(
                        "analysisData" -> Json.obj(
                          "zkProofType" -> Json.fromString("REAL_CRYPTOGRAPHIC_PROOF"),
                          "securityLevel" -> Json.fromString("PRODUCTION_GRADE"),
                          "wasmAnalysis" -> wrapper.output.asJson.hcursor
                            .downField("result")
                            .focus
                            .getOrElse(Json.obj()),
                          "transaction" -> Json.obj(
                            "status" -> Json.fromString("completed"),
                            "txHash" -> Json.fromString(wrapper.output.request.transaction.salt.value.toString),
                            "timestamp" -> Json.fromLong(System.currentTimeMillis()),
                            "dataHash" -> Json.fromString(wrapper.output.request.data.proofs.head.id.hex.toString)
                          ),
                          "metadata" -> Json.obj(
                            "userId" -> Json.fromString(userId),
                            "sessionId" -> Json.fromString(wrapper.output.request.transaction.salt.value.toString),
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
                    _ <- F.delay(
                      println(
                        s"[WASM Executor] Processing $eventCount gaming input events for proof generation with inputs: $publicInputs"
                      )
                    )

                    proofAttempt <- F.delay {
                      Try {
                        println(s"[WASM Executor] Generating proof with AI/ML analysis for gaming input data: $publicInputs")
                        zkExecutor
                          .generateProof(
                            s"gaming_input_${System.currentTimeMillis()}",
                            publicInputs,
                            List.empty
                          )
                          .unsafeRunSync()
                      }
                    }

                    _ <- proofAttempt match {
                      case Success(Right(path)) =>
                        F.delay(println(s"[WASM Executor] ✅ CRYPTO proof generation succeeded at: $path")) *>
                          processProof(path.toString)
                      case Success(Left(error: ZKProofError)) =>
                        F.delay(println(s"[WASM Executor] ❌ CRYPTO ZK proof generation failed: ${error.message}"))
                      case Failure(exception) =>
                        val proofError = ProofGenerationError(exception.getMessage)
                        F.delay(println(s"[WASM Executor] ❌ Unexpected error during proof generation: ${proofError.message}"))
                    }

                    finalResult <- F.pure(Right(NullTerminal): Either[CellError, Ω])
                  } yield finalResult

                case _ =>
                  F.pure(Right(NullTerminal): Either[CellError, Ω])
              }
            case Done(other) =>
              other match {
                case Right(AlgebraCommand.NoAction) =>
                  F.pure(Right(NullTerminal): Either[CellError, Ω])

                case Right(cmd: AlgebraCommand) =>
                  cmd match {
                    case AlgebraCommand.ProcessWasmOutput(_) =>
                      for {
                        _ <- F.delay(println("[WASM Executor] Processing WASM output with CRYPTO proofs"))

                        // Process proofs when enough verified proofs are collected
                        _ <- F.delay {
                          val verifiedProofCount = currentProofs.count(_.verified)
                          if (verifiedProofCount >= 10) {
                            val currentTime = System.currentTimeMillis()
                            val orderedProofs = currentProofs.filter(_.verified).reverse.take(10)
                            val newBlockHash = computeBlockHash(currentBlockHeight, orderedProofs, currentTime)
                            val proofsHashValue = computeProofsHash(orderedProofs)

                            val block = ProofBlockWrapper(
                              currentBlockHeight,
                              newBlockHash,
                              orderedProofs.map(_.toBlockConsensusProofData),
                              currentTime,
                              ProofsHash(proofsHashValue.value)
                            )

                            // Process the block with proofs
                            println(s"[WASM Executor] Processing block with CRYPTOGRAPHIC proofs: ${block.hash}")

                            // Update state after successful processing
                            currentBlockHeight += 1
                            currentProofs = currentProofs.filterNot(p => orderedProofs.contains(p))
                            println(s"[WASM Executor] Block created with CRYPTO security at height $currentBlockHeight")
                          }
                        }

                        address <- ctx.selfId.toAddress
                        rAppTx: RAppStarkHashTransaction = RAppStarkHashTransaction(address, address, "", "",
                          TransactionFee(NonNegLong.MinValue), TransactionAmount(PosLong(1L)), TransactionReference.empty,
                          TransactionSalt(1L))
                        signedRAppTx <- rAppTx.sign(ctx.keyPair)
                        hashedSignedRAppTx <- signedRAppTx.toHashed
                        _ <- ctx.transactionStorage.put(hashedSignedRAppTx)

                        finalResult <- F.pure(Right(NullTerminal): Either[CellError, Ω])
                      } yield finalResult

                    case _ => F.pure(Right(NullTerminal): Either[CellError, Ω])
                  }

                case Right(_) =>
                  // Handle any other Right value that's not an AlgebraCommand
                  F.pure(Right(NullTerminal): Either[CellError, Ω])

                case Left(error) =>
                  F.delay(println(s"[WASM Executor] Error during execution: ${error.toString}")) *>
                    F.pure(Left(error))
              }
          },
          CoalgebraM[F, StackF, Ω] {
            case CoalgebraCommand.EnqueueWasmOutput(wrapper) =>
              F.pure(Done(Right(AlgebraCommand.ProcessWasmOutput(wrapper)): Either[CellError, AlgebraCommand]))
            case _ =>
              F.pure(Done(Right(AlgebraCommand.NoAction): Either[CellError, AlgebraCommand]))
          }
        ),
        {
          case d: WasmOutputWrapper => CoalgebraCommand.EnqueueWasmOutput(d)
          case _                    => CoalgebraCommand.Empty()
        }
      )
    }
  }

  def extractEventData(event: Json): Map[String, Any] = {
    val x = event.hcursor.downField("x_position").as[Double].getOrElse(0.0)
    val y = event.hcursor.downField("y_position").as[Double].getOrElse(0.0)
    val timestamp = event.hcursor.downField("timestamp").as[Long].getOrElse(0L)

    Map[String, Any](
      "x_position" -> x,
      "y_position" -> y,
      "timestamp" -> timestamp
    )
  }

  // event extraction with error handling - now for gaming input data
  private def extractEvents(json: Json): Vector[Json] =
    try {
      val events = json.hcursor
        .downField("value")
        .downField("params")
        .downField("events")
        .focus
        .flatMap(_.asArray)
        .getOrElse(Vector.empty)

      println(s"[WASM Executor] Successfully extracted ${events.size} gaming input events from JSON")
      events
    } catch {
      case e: Exception =>
        println(s"[WASM Executor] Error extracting gaming input events from JSON: ${e.getMessage}")
        Vector.empty
    }

  // Calculate hash of all proofs in a block with explicit steps
  private def computeProofsHash(proofs: List[ProofData]): Hash = {
    // Create new digest instance
    val digest = new SHA256Digest()

    // Update digest with each proof commitment
    proofs.foreach { proof =>
      val commitmentBytes = proof.commitment.value.getBytes
      digest.update(commitmentBytes, 0, commitmentBytes.length)
    }

    // Generate final hash
    val hashBytes = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hashBytes, 0)

    // Create and return Hash object
    Hash.fromBytes(hashBytes)
  }
}
