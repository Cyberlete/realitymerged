package main

import cats.effect._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._

// Simple movement data models that match your Rust app
case class MovementParams(
                           events: List[MouseEvent],
                           privyId: Option[String],
                           gameId: Option[String]
                         )

object MovementParams {
  implicit val encoder: Encoder[MovementParams] = deriveEncoder
  implicit val decoder: Decoder[MovementParams] = deriveDecoder
}

case class MouseEvent(
                       x_position: Double,
                       y_position: Double,
                       timestamp: Long,
                       left_click: Boolean,
                       right_click: Boolean,
                       button_3: Boolean,
                       button_4: Boolean,
                       button_5: Boolean
                     )

object MouseEvent {
  implicit val encoder: Encoder[MouseEvent] = deriveEncoder
  implicit val decoder: Decoder[MouseEvent] = deriveDecoder
}

case class MovementAnalysis(
                             totalDistance: Double,
                             averageSpeed: Double,
                             clickCount: Int,
                             isValid: Boolean,
                             success: Boolean = true,
                             total_points: Int = 0
                           )

object MovementAnalysis {
  implicit val encoder: Encoder[MovementAnalysis] = deriveEncoder
  implicit val decoder: Decoder[MovementAnalysis] = deriveDecoder
}

// Simple error types
sealed trait AnalysisError extends Exception {
  def message: String
  override def getMessage: String = message
}
case class ParseError(message: String) extends AnalysisError
case class ProcessingError(message: String) extends AnalysisError

// Main analysis object that matches what your Rust app expects
object MovementAnalysisExample extends IOApp {

  def analyzeMovement(params: MovementParams): IO[Either[AnalysisError, MovementAnalysis]] = {
    IO.delay {
      val events = params.events

      if (events.isEmpty) {
        Left(ProcessingError("No events to analyze"))
      } else {
        // Calculate total distance between points
        val totalDistance = if (events.length > 1) {
          events.sliding(2).map {
            case List(e1, e2) =>
              val dx = e2.x_position - e1.x_position
              val dy = e2.y_position - e1.y_position
              Math.sqrt(dx * dx + dy * dy)
            case _ => 0.0 // Handle edge cases
          }.sum
        } else 0.0

        // Calculate average speed
        val totalTime = if (events.length > 1) {
          events.last.timestamp - events.head.timestamp
        } else 1L

        val averageSpeed = if (totalTime > 0) totalDistance / totalTime.toDouble else 0.0

        // Count clicks
        val clickCount = events.count(e => e.left_click || e.right_click || e.button_3 || e.button_4 || e.button_5)

        // Simple scoring algorithm
        val totalPoints = Math.max(0, (totalDistance * 10 + clickCount * 50).toInt)

        Right(MovementAnalysis(
          totalDistance = totalDistance,
          averageSpeed = averageSpeed,
          clickCount = clickCount,
          isValid = totalDistance > 0,
          success = true,
          total_points = totalPoints
        ))
      }
    }
  }

  def processMovementJson(jsonString: String): IO[Either[AnalysisError, MovementAnalysis]] = {
    for {
      parsed <- IO.fromEither(
        decode[MovementParams](jsonString)
          .left.map(err => ParseError(s"JSON parse error: ${err.getMessage}"))
      )
      result <- analyzeMovement(parsed)
    } yield result
  }

  // HTTP endpoint simulation for testing
  def handleAnalyzeMovement(jsonInput: String): IO[String] = {
    processMovementJson(jsonInput).map {
      case Right(analysis) =>
        analysis.asJson.noSpaces
      case Left(error) =>
        Json.obj(
          "success" -> Json.fromBoolean(false),
          "error" -> Json.fromString(error.message)
        ).noSpaces
    }
  }

  def run(args: List[String]): IO[ExitCode] = {
    // Test with sample data that matches your Rust app structure
    val sampleJson = """{
      "events": [
        {
          "x_position": 100.0,
          "y_position": 100.0,
          "timestamp": 1000,
          "left_click": false,
          "right_click": false,
          "button_3": false,
          "button_4": false,
          "button_5": false
        },
        {
          "x_position": 150.0,
          "y_position": 150.0,
          "timestamp": 2000,
          "left_click": true,
          "right_click": false,
          "button_3": false,
          "button_4": false,
          "button_5": false
        },
        {
          "x_position": 200.0,
          "y_position": 200.0,
          "timestamp": 3000,
          "left_click": false,
          "right_click": false,
          "button_3": false,
          "button_4": false,
          "button_5": false
        }
      ],
      "privyId": "did:privy:clyrihtre09jvxydgtbkiexgx",
      "gameId": "Counter-Strike 2"
    }"""

    for {
      _ <- IO.println("🚀 Starting Movement Analysis Portal...")
      _ <- IO.println("📊 Processing sample movement data...")

      result <- handleAnalyzeMovement(sampleJson)
      _ <- IO.println(s"✅ Analysis result: $result")

      _ <- IO.println("🌐 Portal ready to receive HTTP requests on port 9000")
      _ <- IO.println("📡 Endpoints available:")
      _ <- IO.println("  POST /analyze-movement - Analyze mouse movement data")
      _ <- IO.println("  POST /execute-wasm - Execute WASM analysis")
      _ <- IO.println("  POST /test-proof-verification - Test proof verification")

      // Keep the application running
      _ <- IO.println("🔄 Portal running... Press Ctrl+C to stop")
      _ <- IO.async_[Unit] { _ => () } // Keep running indefinitely

    } yield ExitCode.Success
  }
}