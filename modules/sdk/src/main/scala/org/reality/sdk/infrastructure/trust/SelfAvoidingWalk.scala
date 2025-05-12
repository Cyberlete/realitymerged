package org.reality.sdk.infrastructure.trust

import scala.util.Random

/** https://en.wikipedia.org/wiki/Node_influence_metric https://en.wikipedia.org/wiki/Self-avoiding_walk
  */
object SelfAvoidingWalk {

  final def sample[A](dist: Map[A, Double]): A = {
    val p = scala.util.Random.nextDouble()
    val it = dist.iterator
    var accum = 0.0
    while (it.hasNext) {
      val (item, itemProb) = it.next()
      accum += itemProb
      if (accum >= p)
        return item // return so that we don't have to search through the whole distribution
    }
    sys.error(f"this should never happen") // needed so it will compile
  }

  def walk_iterative(
    selfId: Int,
    nodeMap: Map[Int, TrustNode]
  ): (Int, Double) = {

    val maxPathLength: Int = nodeMap.size - 1
    val desiredPathLength = Random.nextInt(maxPathLength - 1) + 1

    // Ignore paths that distrust self (maybe consider ignoring paths that distrust immediate
    // neighbors as well? This is where Jaccard distance is important
    // We need to discard walks where large distance exists from previous
    // (i.e. discard information from distant nodes if they distrust nearby nodes that you trust in general)

    // This doesn't need to be recalculated
    val ignoredNodes = nodeMap.filter {
      case (_, trustNode) =>
        trustNode.edges.exists(edge => edge.trust < 0 && edge.dst == selfId)
    }.keySet

    var currentId: Int = selfId
    var currentPathLength: Int = 0
    var visited: Set[Int] = Set()
    var currentTrust: Double = 1d
    var done = false

    // TODO: Visited should have a 'direction' associated to bias the walk not just in terms of trust
    // but also trust derivatives in order to move 'outward' as effectively as possible (to discourage loop formation)
    // otherwise the path length may not matter as the walks will get trapped in the same neighborhood
    // Essentially need topo information from something else processing total edge map
    // Formulate this in terms of DATT expansion.

    while (currentPathLength <= desiredPathLength && !done) {
      currentPathLength += 1
      val n1: TrustNode = nodeMap(currentId)
      val visitedNext = visited + currentId
      val normalEdges = n1.normalizedPositiveEdges(visitedNext)
      if (normalEdges.isEmpty) {
        done = true
      } else {
        val transitionDst = sample(normalEdges)
        if (ignoredNodes.contains(transitionDst)) {
          done = true
        } else {
          val transitionTrust = normalEdges(transitionDst)
          val productTrust = currentTrust * transitionTrust
          visited = visitedNext
          currentId = transitionDst
          currentTrust = productTrust
        }
      }
    }
    currentId -> currentTrust
  }

  def runWalkRaw(selfId: Int, nodes: Seq[TrustNode], numIterations: Int = 100): Array[Double] = {

    val nodeMap = nodes.map { n =>
      n.id -> n
    }.toMap

    val n1 = nodes.head

    def walkFromOrigin() =
      walk_iterative(selfId, nodeMap)
    val numNodes = nodes.maxBy(_.id).id
    val walkScores = Array.fill(numNodes + 1)(0d)

    for (_ <- 0 to numIterations) {
      val (id, trust) = walkFromOrigin()
      if (id != n1.id) {
        walkScores(id) += trust
      }
    }

    walkScores
  }

  // Handle edge case where sum is 0
  def normalizeScores(scores: Array[Double]): Array[Double] = {
    val sumScore = scores.sum
    if (sumScore == 0d) scores
    else scores.map(_ / sumScore)
  }

  def normalizeScoresWithIndex(scores: Array[(Double, Int)]): Array[(Double, Int)] = {
    val sumScore = scores.map(_._1).sum
    if (sumScore == 0d) scores
    else scores.map { case (k, v) => (k / sumScore) -> v }
  }

  // Need to change to fit to a distribution. Simple way to avoid that for now is splitting pos neg
  def normalizeScoresWithNegative(scores: Array[Double]): Array[Double] = {
    val pos = scores.zipWithIndex.filter(_._1 >= 0)
    val neg = scores.zipWithIndex.filter(_._1 < 0)
    normalizeScoresWithIndex(pos).foreach {
      case (s, i) =>
        scores(i) = s
    }
    normalizeScoresWithIndex(neg).foreach {
      case (s, i) =>
        scores(i) = -1 * s
    }
    scores
  }

  def runWalkBatchesFeedback(
    selfId: Int,
    nodes: Seq[TrustNode],
    batchIterationSize: Int = 100,
    epsilon: Double = 1e-6,
    maxIterations: Int = 10
  ): Seq[TrustNode] = {

    var walkScores = runWalkRaw(selfId, nodes, batchIterationSize)
    var walkProbability = normalizeScores(walkScores)

    var merged = walkScores

    var delta = Double.MaxValue
    var iterationNum = 0

    while (delta > epsilon && iterationNum < maxIterations) {

      val batchScores = runWalkRaw(selfId, nodes, batchIterationSize)

      merged = walkScores.zip(batchScores).map { case (s1, s2) => s1 + s2 }

      val renormalized = normalizeScores(merged)

      delta = renormalized.zip(walkProbability).map { case (s1, s2) => Math.pow(Math.abs(s1 - s2), 2) }.sum

      iterationNum += 1
      walkScores = merged
      walkProbability = renormalized
    }

    val selfNode = nodes.filter(_.id == selfId).head
    val others = nodes
      .filterNot(_.id == selfId)
      .map { o =>
        o.id -> o
      }
      .toMap

    val negativeScores = merged.zipWithIndex
      .filterNot(_._2 == selfId)
      .flatMap {
        case (score, id) =>
          val negativeEdges = others.get(id).map(_.negativeEdges).getOrElse(Seq())
          negativeEdges.filterNot(_.dst == selfId).map { ne =>
            val nanTest = ne.trust * score / negativeEdges.size
            ne.dst -> nanTest
          }
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).sum)

    negativeScores.foreach {
      case (id, negScore) =>
        merged(id) += negScore
    }

    val labelEdges = selfNode.edges.filter(_.isLabel)
    val labelDst = labelEdges.map(_.dst)

    val doNormalizeScoresWithNegative = normalizeScoresWithNegative(merged)

    val renormalizedAfterNegative = doNormalizeScoresWithNegative.zipWithIndex.filterNot {
      case (score, id) => labelDst.contains(id)
    }

    val newEdges = renormalizedAfterNegative.map {
      case (score, id) =>
        TrustEdge(selfId, id, score)
    }

    val updatedSelfNode = selfNode.copy(
      edges = labelEdges ++ newEdges
    )
    val res = others.values.toSeq :+ updatedSelfNode

    res
  }

  def runWalkFeedbackUpdateSingleNode(
    selfId: Int,
    nodes: Seq[TrustNode],
    batchIterationSize: Int = 100,
    epsilon: Double = 1e-5,
    maxIterations: Int = 10,
    feedbackCycles: Int = 3
  ): TrustNode = {

    var nodesCycle = nodes
    if (nodesCycle.size > 2) { // note, need min of 3 nodes
      (0 until feedbackCycles).foreach { cycle =>
        nodesCycle = runWalkBatchesFeedback(selfId, nodes, batchIterationSize, epsilon, maxIterations)
      }
    }
    val res: TrustNode = nodesCycle.filter(_.id == selfId).head

    res
  }
}
