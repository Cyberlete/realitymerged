package org.reality.combined.node

import io.circe.{Encoder, Json}

sealed trait ToggleNodeResult

object ToggleNodeResult {
  implicit val encoder: Encoder[ToggleNodeResult] = Encoder.instance { result =>
    val jsonValue = Json.fromString {
      result match {
        case Started => "Started"
        case Stopped => "Stopped"
        case Failed  => "Failed"
      }
    }

    Json.obj("value" -> jsonValue)
  }

  case object Started extends ToggleNodeResult
  case object Stopped extends ToggleNodeResult
  case object Failed extends ToggleNodeResult
}
