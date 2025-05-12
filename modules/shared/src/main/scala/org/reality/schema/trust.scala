package org.reality.schema

import cats.Show

import org.reality.schema.TrustValueRefinement.TrustValueRefinement
import org.reality.schema.peer.PeerId

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._

object trust {

  type TrustValue = Double Refined TrustValueRefinement

  implicit def showTrustValue: Show[TrustValue] = s => s"TrustValue(value=${s.value})"

  @derive(decoder, encoder, show)
  case class TrustInfo(
    trustLabel: Option[Double] = None,
    predictedTrust: Option[Double] = None,
    observationAdjustmentTrust: Option[Double] = None,
    currentInfluenceMeasures: Map[Long, Map[PeerId, Double]] = Map.empty,
    heightToEntropyRates: Map[Long, Map[PeerId, Double]] = Map.empty
  ) {

    val publicTrust: Option[Double] =
      trustLabel
        .map(t => Math.max(-1, t + observationAdjustmentTrust.getOrElse(0d)))
        .orElse(observationAdjustmentTrust.map(t => Math.max(-1, t)))
  }

  @derive(decoder, encoder, show)
  case class PublicTrust(
    labels: Map[PeerId, Double]
  )
}
