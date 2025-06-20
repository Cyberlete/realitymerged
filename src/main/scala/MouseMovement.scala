import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

import java.time.Instant
//import org.reality.combined.util

// MongoDB Date Models
case class MongoDate($date: String)

object MongoDate {
  implicit val encoder: Encoder[MongoDate] = deriveEncoder
  implicit val decoder: Decoder[MongoDate] = deriveDecoder

  implicit val instantEncoder: Encoder[Instant] = Encoder.instance { instant =>
    Json.obj("$date" -> Json.fromString(instant.toString))
  }

  implicit val instantDecoder: Decoder[Instant] = Decoder.instance { cursor =>
    cursor.downField("$date").as[String].map(Instant.parse)
  }
}

// Movement Tracking Models
case class ScreenResolution(width: Int, height: Int)
object ScreenResolution {
  implicit val encoder: Encoder.AsObject[ScreenResolution] = deriveEncoder
  implicit val decoder: Decoder[ScreenResolution] = deriveDecoder
}

case class Metadata(
                     playerId: String,
                     deviceType: String,
                     gameType: String,
                     screenResolution: ScreenResolution,
                     browserInfo: String,
                     sessionStartTime: MongoDate,
                     sessionEndTime: MongoDate,
                     movementPattern: String
                   )
object Metadata {
  implicit val encoder: Encoder.AsObject[Metadata] = deriveEncoder
  implicit val decoder: Decoder[Metadata] = deriveDecoder
}

case class MouseEvent(
                       x_position: Int,
                       y_position: Int,
                       timestamp: MongoDate
                     )
object MouseEvent {
  implicit val encoder: Encoder.AsObject[MouseEvent] = deriveEncoder
  implicit val decoder: Decoder[MouseEvent] = deriveDecoder
}

case class Movement(event: MouseEvent)
object Movement {
  implicit val encoder: Encoder.AsObject[Movement] = deriveEncoder
  implicit val decoder: Decoder[Movement] = deriveDecoder
}

case class MouseMovement(
                          sessionId: String,
                          status: String,
                          movements: List[Movement],
                          metadata: Metadata
                        )
object MouseMovement {
  implicit val encoder: Encoder.AsObject[MouseMovement] = deriveEncoder
  implicit val decoder: Decoder[MouseMovement] = deriveDecoder
}