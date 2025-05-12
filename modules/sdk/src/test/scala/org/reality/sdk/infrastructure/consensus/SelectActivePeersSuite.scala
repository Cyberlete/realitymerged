package org.reality.sdk.infrastructure.consensus

import cats.Show
import cats.syntax.show._

import org.reality.schema.peer.PeerId
import org.reality.security.hash.Hash
import org.reality.security.hex.Hex

import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object SelectActivePeersSuite extends SimpleIOSuite with Checkers {

  override def maxParallelism = 1

  implicit val show: Show[(Hash, Map[PeerId, NonNegLong])] = Show.show {
    case (hash, cBal) =>
      s"(${hash.show}, ${cBal.toList.map { case (p, b) => s"${p.show} -> ${b.value.show}" }.show})"
  }

  test("multiple calls should return the same results") {

    forall(facilitatorsGen) {
      case (hash, candidateBalances) =>
        val result1 = SelectActivePeers.selectPeers(
          hash,
          Map.empty,
          candidateBalances
        )
        val result2 = SelectActivePeers.selectPeers(
          hash,
          Map.empty,
          candidateBalances
        )
        expect.eql(result1, result2)
    }
  }

  test("at least two consecutive calls return the same results") {

    forall(facilitatorsGen) {
      case (hash, candidateBalances: Map[PeerId, NonNegLong]) =>
        val result1 = SelectActivePeers.selectPeers(hash, Map.empty, candidateBalances)
        val result2 = SelectActivePeers.selectPeers(hash, Map.empty, candidateBalances)
        val result3 = SelectActivePeers.selectPeers(hash, Map.empty, candidateBalances)
        expect.eql(result1, result2) || expect.eql(result2, result3)
    }
  }

  test("number of calls equal to size of candidates should return the same results") {

    forall(facilitatorsGen) {
      case (hash, candidateBalances) =>
        val result1 = SelectActivePeers.selectPeers(hash, Map.empty, candidateBalances)
        val result2 = SelectActivePeers.selectPeers(
          hash,
          Map.empty,
          candidateBalances
        )
        expect.eql(result1, result2)
    }
  }

  pureTest("having only candidates with 0 balances shouldn't break the selection") {
    val peer1 = PeerId(Hex("aaa"))
    val peer2 = PeerId(Hex("bbb"))
    val peer3 = PeerId(Hex("ccc"))

    val candidateBalances = Map(
      peer1 -> NonNegLong.MinValue,
      peer2 -> NonNegLong.MinValue,
      peer3 -> NonNegLong.MinValue
    )

    val influenceMaps = Map(
      peer1 -> Map(peer1 -> 1.0, peer2 -> 1.0, peer3 -> 1.0),
      peer2 -> Map(peer1 -> 1.0, peer2 -> 1.0, peer3 -> 1.0),
      peer3 -> Map(peer1 -> 1.0, peer2 -> 1.0, peer3 -> 1.0)
    )

    val actualResult = SelectActivePeers.selectPeers(Hash.empty, influenceMaps, candidateBalances)
    val expectedResult = Set(peer1, peer2, peer3)

    expect.eql(expectedResult, actualResult)
  }

  def facilitatorsGen: Gen[(Hash, Map[PeerId, NonNegLong])] = for {
    hash <- Arbitrary.arbitrary[Hash]
    candidates <- Gen.choose(1, 1000).flatMap(size => Gen.containerOfN[List, PeerId](size, arbitrary[PeerId]))
    balances <- Gen.containerOfN[List, NonNegLong](candidates.size, arbitrary[NonNegLong])
    candidateBalances = candidates.zip(balances).toMap
  } yield (hash, candidateBalances)
}
