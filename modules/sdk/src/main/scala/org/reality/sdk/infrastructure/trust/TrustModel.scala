package org.reality.sdk.infrastructure.trust

import scala.collection.immutable
import scala.collection.mutable.ListBuffer

import org.reality.schema.peer.PeerId
import org.reality.schema.trust.TrustInfo
import org.reality.sdk.infrastructure.trust.TrustNode
import org.reality.security.hash
import org.reality.security.hash.Hash

import breeze.linalg.DenseVector
import breeze.stats.{mean, variance}

object TrustModel {
  def differences(l: List[Double]): List[Double] = l match {
    case a :: (rest @ b :: _) => (b - a) :: differences(rest)
    case _                    => Nil
  }

  def findSuccessiveDiffPlanes(cumSumSorted: List[Double], mu: Double) = {
    val len = cumSumSorted.length
    val flatPlanes = new ListBuffer[(Int, Int)]()
    var startingPlaneIdx = 0
    var curPlaneIdx = 0
    for (i <- 0 to len - 2) {
      val curByIdx = cumSumSorted(i)
      val nextByIdx = cumSumSorted(i + 1)
      val diff = curByIdx - nextByIdx
      val magnitude = if (diff < 0.0) diff * -1.0 else diff
      if (magnitude > mu) {
        flatPlanes.addOne((startingPlaneIdx, i))
        startingPlaneIdx = i + 1
        curPlaneIdx = i + 1
      } else {
        curPlaneIdx += 1
      }
    }
    flatPlanes.toList
  } // todo look for sections in a list where diff between each element is <= mu

  def removeOutlierNodes(cumSum: List[Double]): List[Double] = {
    val cumSumSorted = cumSum.sorted

    if (cumSumSorted.size > 3) { // todo move to codebase
      val totalPopulation = cumSumSorted.length
      val data = DenseVector(cumSumSorted.toArray)
      val mu = mean(data)
      val samp_var = variance(data)
      val pop_var = samp_var * (totalPopulation.toDouble - 1) / (totalPopulation.toDouble)
      val pop_std = math.sqrt(pop_var)

      val maxPlaneDiff = if (math.abs(mu) > 0.1) math.abs(mu) else 0.1
      val testPlanes = findSuccessiveDiffPlanes(cumSumSorted, maxPlaneDiff) // findSuccessiveDiffPlanes(cumSumSorted, math.abs(mu))//
      val maxPlaneIdxs = {
        val planesOpt = testPlanes.maxByOption(tup => tup._2 - tup._1) // todo empty maxby bug
        if (planesOpt.isEmpty) (0, 0) else planesOpt.get
      }
      val maxPlaneLen = maxPlaneIdxs._2 - maxPlaneIdxs._1
      val planeLeftBias = math.abs(1.0 - (maxPlaneIdxs._1.toDouble / (totalPopulation - maxPlaneIdxs._2.toDouble)))
      val planeRightBias = math.abs(1.0 - ((totalPopulation - maxPlaneIdxs._2.toDouble) / maxPlaneIdxs._1.toDouble))

      val successiveDiffs = differences(cumSumSorted)
      val eigenvalues = successiveDiffs.map { diff =>
        if (diff < 0.0) diff * -1.0
        else diff
      } // magnitude of influence of each node (eigenvector)

      val sortedEigenvalues = eigenvalues.sorted

      // TODO: not sure if it makes sense, this just takes first 3 score differences (lowest values I think)
      val List(thirdGraphCutEigenvalue, secondGraphCutEigenvalue, maxGraphCutEigenvalue) = sortedEigenvalues.takeRight(3)

      val maxGraphCutIdx = eigenvalues.indexOf(maxGraphCutEigenvalue)
      val secondGraphCutIdx = eigenvalues.indexOf(secondGraphCutEigenvalue)
      val thirdGraphCutIdx = eigenvalues.indexOf(thirdGraphCutEigenvalue)

      val maxLeft = cumSumSorted.take(maxGraphCutIdx)
      val maxRight = cumSumSorted.drop(maxGraphCutIdx + 1)

      val secondLeft = cumSumSorted.take(secondGraphCutIdx)
      val secondRight = cumSumSorted.drop(secondGraphCutIdx + 1)

      val thirdLeft = cumSumSorted.take(thirdGraphCutIdx)
      val thirdRight = cumSumSorted.drop(thirdGraphCutIdx + 1)

      val maxPartitionPopulationDiff = math.abs(1 - (maxLeft.size.toDouble / maxRight.size.toDouble))
      val secondPartitionPopulationDiff = math.abs(1 - (secondLeft.size.toDouble / secondRight.size.toDouble))
      val thirdPartitionPopulationDiff = math.abs(1 - (thirdLeft.size.toDouble / thirdRight.size.toDouble))

      val popDiffs = List(maxPartitionPopulationDiff, secondPartitionPopulationDiff, thirdPartitionPopulationDiff)

      val res =
        if (maxPlaneLen.toDouble > totalPopulation * 0.1 && (planeLeftBias < 0.9 || planeRightBias < 0.9))
          cumSumSorted.slice(0, maxPlaneIdxs._1) ++ cumSumSorted.drop(maxPlaneIdxs._2 + 1)
        else if (math.abs(mu / pop_var) > 1.3 || math.abs(mu / pop_var) < math.pow(10, -17)) {
          cumSumSorted
        } else if (
          (maxGraphCutEigenvalue - secondGraphCutEigenvalue) > pop_std * 0.1 // todo pop_std*1.2?
          || popDiffs.min == maxPartitionPopulationDiff
        ) {
          if (math.abs(maxLeft.sum) - math.abs(maxRight.sum) > pop_std)
            maxRight // todo instead of sum look at each partition's diff from avg entropy rates
          ;
          else maxLeft
        } else if (popDiffs.min == secondPartitionPopulationDiff) {

          if (math.abs(secondLeft.sum) - math.abs(secondRight.sum) > pop_std)
            secondRight // todo instead of sum look at each partition's diff from avg entropy rates
          else secondLeft

        } else if (popDiffs.min == thirdPartitionPopulationDiff) {

          if (math.abs(thirdLeft.sum) - math.abs(thirdRight.sum) > pop_std)
            thirdRight // todo instead of sum look at each partition's diff from avg entropy rates
          else thirdLeft
        } else cumSumSorted

      if (res.length > 0.1 * totalPopulation) res
      else cumSumSorted
    } else cumSumSorted
  }
  // todo if returning less than 10%, return whole amount

  def calculateTrust(trustNodes: List[TrustNode], selfPeerIdx: Int): Map[Int, Double] = {

    val eigenTrustScores = EigenTrust.calculate(trustNodes)

    val dattScores = DATT.calculate(DATT.convert(trustNodes), selfPeerIdx)

    val walkScores = SelfAvoidingWalk
      .runWalkFeedbackUpdateSingleNode(selfPeerIdx, trustNodes)
      .edges
      .map(e => e.dst -> e.trust)
      .toMap

    walkScores.map {
      case (id, score) =>
        id ->
          ((score + dattScores.getOrElse(id, 0d) + eigenTrustScores.getOrElse(id, 0d)) / 3)
    }
  }

  def calculateTrust(trust: Map[PeerId, TrustInfo], selfPeerId: PeerId): Map[PeerId, Double] = {
    val selfTrustLabels = trust.flatMap { case (peerId, trustInfo) => trustInfo.publicTrust.map(peerId -> _) }
    val allNodesTrustLabels =
      trust.view.mapValues(v => noralizeInfluenceRounds(v.currentInfluenceMeasures)).toMap + (selfPeerId -> selfTrustLabels)

    val peerIdToIdx = allNodesTrustLabels.keys.zipWithIndex.toMap
    val idxToPeerId = peerIdToIdx.map(_.swap)
    val selfPeerIdx = peerIdToIdx(selfPeerId)

    val trustNodes = allNodesTrustLabels.map {
      case (peerId, labels) =>
        TrustNode(
          peerIdToIdx(peerId),
          0,
          0,
          labels.map {
            case (pid, label) =>
              TrustEdge(peerIdToIdx(peerId), peerIdToIdx(pid), label, peerId == selfPeerId)
          }.toList
        )
    }.toList
    calculateTrust(trustNodes, selfPeerIdx).map { case (k, v) => idxToPeerId(k) -> v }
  }

  def entropyRate(proposalBlocks: Map[PeerId, Seq[Hash]]): Map[PeerId, Double] = {
    val allProposers = proposalBlocks.size
    val proposalHashToProposers: Map[Hash, immutable.Iterable[PeerId]] = proposalBlocks.flatMap {
      case (k, v) => v.map((_, k))
    }.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }

    val totalEntropyRateSeries: Map[hash.Hash, Double] = proposalHashToProposers.map {
      case (k, v: immutable.Iterable[PeerId]) =>
        val p: Double = (v.size / allProposers).toDouble
        val r: Double = -p * math.log(p)
        (k, r)
    }

    val proposersPrevTrustWeighted: Map[PeerId, Double] = proposalBlocks.map {
      case (peerId, blocks) => // todo remove options
        val newEnt: Double = blocks.map(totalEntropyRateSeries(_)).sum
        (peerId, newEnt)
    }
    proposersPrevTrustWeighted
  }

  def merge[A, B, C](c: (B, B) => C)(a: Map[A, B], b: Map[A, B]) =
    for {
      key <- a.keySet ++ b.keySet
      aval <- a.get(key)
      bval <- b.get(key)
    } yield c(aval, bval)

  def noralizeInfluenceRounds(rounds: Map[Long, Map[PeerId, Double]]) = {
    val allRounds: Map[PeerId, Iterable[(PeerId, Double)]] = rounds.values.flatten.groupBy(_._1)
    val normalizedRounds: Map[PeerId, Double] = allRounds.map(scores => (scores._1, scores._2.map(_._2).sum / scores._2.size))
    normalizedRounds
  }
  def calculateEntropicTrust(trust: Map[PeerId, TrustInfo], selfPeerId: PeerId): Map[PeerId, Double] = {

    val proposersPrevTrustWeighted: Map[PeerId, Double] = trust.map {
      case (peerId, trustInfo: TrustInfo) => // todo remove options
        (peerId, noralizeInfluenceRounds(trustInfo.currentInfluenceMeasures).getOrElse(peerId, 0L))
    } // todo ensure same order as in map below
    val entropyRateNormalized = SelfAvoidingWalk.normalizeScores(proposersPrevTrustWeighted.values.toArray)
    val addNormalEndToPrevTrust = trust.values.map(_.publicTrust.getOrElse(0.0)).toArray.zip(entropyRateNormalized).map {
      case (t, e) => t + e
    }
    val normalizedSum: Array[Double] = SelfAvoidingWalk.normalizeScores(addNormalEndToPrevTrust)
    val normalizedTrust: Map[PeerId, Double] = trust.keys.zip(normalizedSum).toMap

    // todo weight each peer id by the entropy of the hashes they proposed, normalize -1 to 1, add to previous scores, renormalize

    val allNodesTrustLabels: Map[PeerId, Map[PeerId, Double]] = trust.view
      .mapValues(v => noralizeInfluenceRounds(v.currentInfluenceMeasures)) // todo ensure peerLabels are same as in snapshots
      .toMap + (selfPeerId -> normalizedTrust)

    val peerIdToIdx = allNodesTrustLabels.keys.zipWithIndex.toMap
    val idxToPeerId = peerIdToIdx.map(_.swap)
    val selfPeerIdx = peerIdToIdx(selfPeerId)

    val trustNodes = allNodesTrustLabels.map {
      case (peerId, labels) =>
        TrustNode(
          peerIdToIdx(peerId),
          0,
          0,
          labels.map {
            case (pid, label) =>
              TrustEdge(peerIdToIdx(peerId), peerIdToIdx(pid), label, peerId == selfPeerId)
          }.toList
        )
    }.toList
    calculateTrust(trustNodes, selfPeerIdx).map { case (k, v) => idxToPeerId(k) -> v }
  }

}
