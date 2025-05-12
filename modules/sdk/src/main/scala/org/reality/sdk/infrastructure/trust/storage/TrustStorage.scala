package org.reality.sdk.infrastructure.trust.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._

import scala.collection.immutable.Map

import org.reality.schema.peer.PeerId
import org.reality.schema.trust.TrustInfo
import org.reality.sdk.domain.trust.storage.TrustStorage

object TrustStorage {

  def make[F[_]: Monad: Ref.Make]: F[TrustStorage[F]] =
    for {
      trust <- Ref[F].of[Map[PeerId, TrustInfo]](Map.empty)
    } yield make(trust)

  def make[F[_]: Monad](influenceCache: Ref[F, Map[PeerId, TrustInfo]]): TrustStorage[F] =
    new TrustStorage[F] {

      def getInfluenceCache: F[Map[PeerId, TrustInfo]] = influenceCache.get

      def cleanCache(): F[Unit] = influenceCache.set(Map.empty[PeerId, TrustInfo])

      def updatePredictedTrust(newTrust: Map[PeerId, Double], thisNode: PeerId): F[Unit] = influenceCache.update { current =>
        val allPeers = current.keySet ++ newTrust.keySet + thisNode

        allPeers.map { pId =>
          val prevTrust = current.getOrElse(pId, TrustInfo())
          val updatedPredictedTrust = newTrust.get(pId).orElse(prevTrust.predictedTrust)

          val updatedTrust = if (pId == thisNode) {
            val prevMeasures: Map[Long, Map[PeerId, Double]] = prevTrust.currentInfluenceMeasures
            val numIterations = prevMeasures.keys.toList.maxByOption(identity)

            val checkNumIterations = numIterations.getOrElse(0L) + 1

            val newLevel: Map[Long, Map[PeerId, Double]] = Map(checkNumIterations -> newTrust)
            val newTrustInfo = prevTrust.copy(
              currentInfluenceMeasures = prevMeasures ++ newLevel,
              predictedTrust = updatedPredictedTrust
            )

            newTrustInfo
          } else prevTrust.copy(predictedTrust = updatedPredictedTrust)

          pId -> updatedTrust
        }.toMap
      }

      def updateInfluenceCache(entropyRates: Map[PeerId, Double], height: Long): F[Unit] = influenceCache.update {
        t: Map[PeerId, TrustInfo] =>
          val entropyUpdates: Map[PeerId, TrustInfo] = entropyRates.map {
            case (k, v) =>
              val prevTrust: TrustInfo = t.getOrElse(k, TrustInfo())

              val prevHeightToEntropyRates: Map[Long, Map[PeerId, Double]] = prevTrust.heightToEntropyRates

              val newTrustInfo = prevTrust.copy(heightToEntropyRates = prevHeightToEntropyRates ++ Map(height -> entropyRates))

              k -> newTrustInfo
          }

          val res = t ++ entropyUpdates

          res
      }
    }
}
