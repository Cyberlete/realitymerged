package org.reality.sdk.infrastructure.consensus

import java.math.BigInteger

import cats.Id
import cats.syntax.all._

import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.trust.TrustModel.removeOutlierNodes
import org.reality.security.hash.Hash
import org.reality.security.hex.Hex

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}

object SelectActivePeers {
  val selectionInterval: PosLong = PosLong(20)
  val reductionLimit: PosInt = PosInt(10)

  type Scores = Map[PeerId, Double]

  // TODO: analyze best collections to use and algorithms to reduce computation complexity
  def selectPeers(
    lastArtifactHash: Hash,
    peerToInfluenceMap: Map[PeerId, Scores],
    candidateBalances: Map[PeerId, NonNegLong]
  ): Set[PeerId] = {
    // todo config for cluster size, max active = sqrt(candidates.size)

    val influenceMaps = peerToInfluenceMap.values

    val cumSumScores = candidateBalances.map {
      case (pId, balance) =>
        val score =
          influenceMaps.foldLeft(0.0d) {
            case (aggScore, influenceMap) =>
              aggScore + influenceMap.getOrElse(pId, -1.0)
          }

        pId -> (score, balance.value)
    }.toList.sorted

    val allScores = cumSumScores.map { case (_, (s, _)) => s }

    val keptScores = removeOutlierNodes(allScores).toSet
    val keptCandidates = cumSumScores.filter { case (_, (score, _)) => keptScores.contains(score) }.map {
      case (pId, (_, balance)) => (pId, balance)
    }

    val nextFacilitatorCount: Int = {
      // TODO: take size from kept or from all candidates?
      val keptCandidatesSize = keptCandidates.size
      val isAboveLimit = keptCandidatesSize > reductionLimit.value
      def surplus = keptCandidatesSize - reductionLimit.value

      if (isAboveLimit) reductionLimit.value + math.sqrt(surplus.toDouble).intValue
      else keptCandidatesSize
    }

    val (bottomTierByStake, topTierByStake) = cumSumWhile(keptCandidates)

    val topTierSorted = topTierByStake.sortBy(p => (xOrSorted(p, lastArtifactHash), p))

    val bottomTierSorted = bottomTierByStake.sortBy(p => (xOrSorted(p, lastArtifactHash), p))

    topTierSorted
      .map(_.some)
      .zipAll(bottomTierSorted.map(_.some), None, None)
      .flatMap { case (a, b) => List(a, b) }
      .flatten
      .take(nextFacilitatorCount)
      .toSet
  }

  private def xOrSorted(peerId: PeerId, hash: Hash): Long =
    new BigInteger(peerId.value.toBytes).longValue() ^ new BigInteger(Hex(hash.value).toBytes).longValue()

  // use facilitators hash for propHash, use keyHash for majSelected

  private def cumSumWhile(idBalances: List[(PeerId, Long)]): (Seq[PeerId], Seq[PeerId]) = {
    val stakeTotal = idBalances.map(_._2).sum
    val stakeHalf = stakeTotal / 2
    val idBalancesSorted = idBalances.sortBy(-_._2)

    (idBalancesSorted, 0L, Seq.empty[PeerId])
      .tailRecM[Id, (Seq[PeerId], Seq[PeerId])] {
        case (bottomTier, aggStake, topTier) if aggStake >= stakeHalf =>
          (bottomTier.map(_._1), topTier).asRight

        case ((pId, balance) :: next, aggStake, topTierAgg) =>
          (next, aggStake + balance, topTierAgg.prepended(pId)).asLeft

        case (Nil, _, topTier) =>
          (Seq.empty, topTier).asRight
      }
  }
}
