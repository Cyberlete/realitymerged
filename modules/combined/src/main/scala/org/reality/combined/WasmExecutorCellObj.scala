package org.reality.combined

import java.net.{ConnectException, SocketTimeoutException}
import java.nio.file.{Files, Paths}

import cats.effect.std.{Queue, Random}
import cats.effect.unsafe.IORuntime
import cats.effect.{Async, IO}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import org.reality.combined.{ProofGenerationError, ZKProofError}
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.{
  ProofBlockWrapper,
  ProofData => BlockConsensusProofData,
  WasmOutputWrapper
}
import org.reality.dag.l1.domain.consensus.block.{AlgebraCommand, BlockConsensusContext, CoalgebraCommand, StateChannelCell}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel.{Cell, CellError, Done, More, StackF, Ω}
import org.reality.security.SecurityProvider
import org.reality.security.hash.{Hash, ProofsHash}

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.circe.Json
import io.circe.syntax._
import org.bouncycastle.crypto.digests.SHA256Digest
import sttp.client3.{HttpURLConnectionBackend, UriContext, basicRequest}

object WasmExecutorCellObj extends StateChannelCell {
  implicit val runtime: IORuntime = cats.effect.unsafe.implicits.global

  // Validator API configuration
  private val API_ENDPOINT = System.getProperty("validator.api.endpoint", "http://161.35.184.143:5001/api/movement-analysis")
  private val CONNECTION_TIMEOUT = 5.seconds // Using scala.concurrent.duration._
  private val SKIP_API_CALLS = API_ENDPOINT.toLowerCase == "none" || System.getProperty("validator.skip.api", "false").toBoolean

  // Added for proofs into blocks
  case class ProofData(
    proofPath: String,
    metadataPath: String,
    commitment: Hash,
    timestamp: Long
  ) {
    def toBlockConsensusProofData: BlockConsensusProofData =
      BlockConsensusProofData(
        proofPath,
        metadataPath,
        commitment,
        timestamp
      )
  }

  // Added for proof into blocks helper
  private def extractProofInfo(
    proofPath: String,
    metadataPath: String,
    timestamp: Long
  ): ProofData = {
    val digest = new SHA256Digest()
    val proofContent = Files.readAllBytes(Paths.get(proofPath))
    digest.update(proofContent, 0, proofContent.length)
    val hashBytes = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hashBytes, 0)

    ProofData(
      proofPath = proofPath,
      metadataPath = metadataPath,
      commitment = Hash.fromBytes(hashBytes),
      timestamp = timestamp
    )
  }

  // Added for proofs into blocks
  private def computeBlockHash(height: Long, proofs: List[ProofData], timestamp: Long): Hash = {
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

    val hash = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hash, 0)
    Hash.fromBytes(hash)
  }

  def mkCell[F[_]](ctx: BlockConsensusContext[F])(
    implicit F: Async[F],
    S: SecurityProvider[F],
    R: Random[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = {
    val zkExecutor = new ZKWasmExecutor[IO]
    var currentBlockHeight: Long = 0
    var currentProofs: List[ProofData] = List.empty

    def processProof(proofPath: String): F[Unit] = for {
      _ <- F.delay(println(s"[WASM Executor] Proof generated successfully at: $proofPath"))

      proofInfo <- F.delay(
        extractProofInfo(
          proofPath,
          s"$proofPath.meta",
          System.currentTimeMillis()
        )
      )

      _ <- F.delay {
        currentProofs = proofInfo :: currentProofs
        println(s"[WASM Executor] Added proof to current block: ${proofInfo.commitment}")
      }

      _ <-
        if (currentProofs.length >= 10) {
          for {
            timestamp <- F.delay(System.currentTimeMillis())
            orderedProofs = currentProofs.reverse
            blockHash = computeBlockHash(currentBlockHeight, orderedProofs, timestamp)

            block = ProofBlockWrapper(
              height = currentBlockHeight,
              hash = blockHash,
              proofs = orderedProofs.map(_.toBlockConsensusProofData),
              timestamp = timestamp,
              proofsHash = ProofsHash(computeProofsHash(orderedProofs).value)
            )

            // Create queue for block processing
            queue <- Queue.unbounded[F, ProofBlockWrapper]
            _ <- queue.offer(block)

            _ <- F.delay {
              println(s"""
                         |[WASM Executor] Creating new block:
                         |Height: $currentBlockHeight
                         |Hash: ${blockHash}
                         |Proofs included: ${orderedProofs.length}
                         |Timestamp: $timestamp
                         |""".stripMargin)
              currentBlockHeight = currentBlockHeight + 1
              currentProofs = List.empty
            }
          } yield ()
        } else F.unit
    } yield ()

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
              .readTimeout(CONNECTION_TIMEOUT) // Using Duration type now

            val startTime = System.currentTimeMillis()
            val response = request.send(backend) // Using the backend variable correctly
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
                    _ <- F.delay(println("[WASM Executor] Starting WASM output processing"))

                    // Extract events and identifiers
                    events = extractEvents(wrapper.output.request.data.asJson)
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
                      .getOrElse("default")

                    userId = privyId.getOrElse(wrapper.output.request.data.proofs.head.id.hex.toString)

                    _ <- F.delay(println(s"[WASM Executor] Processing data for user ID: $userId (Game: $gameId)"))

                    // Prepare analysis data for validator API
                    analysisData <- F.delay(
                      Json.obj(
                        "analysisData" -> Json.obj(
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
                            "count" -> Json.fromInt(events.size)
                          )
                        ),
                        "events" -> Json.fromValues(events)
                      )
                    )

                    // Send data to validator API (non-blocking)
                    _ <- sendToValidatorApi(analysisData)

                    // Continue with proof generation (original code)
                    publicInputs = events.zipWithIndex.map {
                      case (event, idx) => (s"event_$idx", extractEventData(event))
                    }.toList

                    _ <- F.delay(println(s"[WASM Executor] Processing ${publicInputs.length} events for proof generation"))

                    proofAttempt <- F.delay {
                      Try {
                        zkExecutor
                          .generateProof(
                            s"movement_${System.currentTimeMillis()}",
                            publicInputs,
                            List.empty
                          )
                          .unsafeRunSync()
                      }
                    }

                    _ <- proofAttempt match {
                      case Success(Right(path)) =>
                        F.delay(println(s"[WASM Executor] Proof generation succeeded")) *>
                          processProof(path.toString)
                      case Success(Left(error: ZKProofError)) =>
                        F.delay(println(s"[WASM Executor] ZK proof generation failed: ${error.message}"))
                      case Failure(exception) =>
                        val proofError = ProofGenerationError(exception.getMessage)
                        F.delay(println(s"[WASM Executor] Unexpected error during proof generation: ${proofError.message}"))
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
                        _ <- F.delay(println("[WASM Executor] Processing WASM output"))

                        // Process proofs when enough are collected
                        _ <-
                          if (currentProofs.length >= 10) {
                            for {
                              currentTime <- F.delay(System.currentTimeMillis())
                              orderedProofs = currentProofs.reverse
                              newBlockHash = computeBlockHash(currentBlockHeight, orderedProofs, currentTime)

                              _ <- {
                                val block = ProofBlockWrapper(
                                  currentBlockHeight,
                                  newBlockHash,
                                  orderedProofs.map(_.toBlockConsensusProofData),
                                  currentTime,
                                  ProofsHash(computeProofsHash(orderedProofs).value)
                                )

                                // Process the block
                                F.delay(println(s"[WASM Executor] Processing block: ${block.hash}"))
                              }

                              // Update state after successful processing
                              _ <- F.delay {
                                currentBlockHeight += 1
                                currentProofs = List.empty
                                println(s"[WASM Executor] Block created with height $currentBlockHeight")
                              }
                            } yield ()
                          } else F.unit

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

  private def extractEventData(event: Json): Map[String, Any] = {
    val x = event.hcursor.downField("x_position").as[Double].getOrElse(0.0)
    val y = event.hcursor.downField("y_position").as[Double].getOrElse(0.0)
    val timestamp = event.hcursor.downField("timestamp").as[Long].getOrElse(0L)

    Map[String, Any](
      "x_position" -> x,
      "y_position" -> y,
      "timestamp" -> timestamp
    )
  }

  private def extractEvents(json: Json): Vector[Json] =
    json.hcursor
      .downField("value")
      .downField("params")
      .downField("events")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)

  private def computeProofsHash(proofs: List[ProofData]): Hash = {
    val digest = new SHA256Digest()
    proofs.foreach { proof =>
      digest.update(proof.commitment.value.getBytes, 0, proof.commitment.value.getBytes.length)
    }
    val hash = new Array[Byte](digest.getDigestSize)
    digest.doFinal(hash, 0)
    Hash.fromBytes(hash)
  }
}
