package org.reality.infrastructure.snapshot

import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import org.reality.net.domain.block.generators._
import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.ext.crypto._
import org.reality.ext.serializing._
import org.reality.schema._
import org.reality.schema.generators._
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.transaction.RewardTransaction
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.syntax._
import io.estatico.newtype.ops._
import org.reality.dag.domain.block.NETBlockAsActiveTip
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotStateProof}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotSerializationSuite extends SimpleIOSuite with Checkers {

  val expectedHash: Hash = Hash("6044e3fec0de6b8e8f240dd89c0905ae9adeebdc1d758e80a200ad74fa485121")
  val kryoFilename: String = expectedHash.coerce
  val jsonFilename: String = s"${expectedHash.coerce}.json"

  val arbitraryGlobalGen = for {
    snapshotOrdinal <- arbitrary[SnapshotOrdinal]
    height <- arbitrary[Height]
    subHeight <- arbitrary[SubHeight]
    lastSnapshotHash <- arbitrary[Hash]
    blocks <- Gen.nonEmptyListOf(arbitrary[NETBlockAsActiveTip])
    reward <- arbitrary[RewardTransaction]
    snapshotTips <- arbitrary[SnapshotTips]
    stateProof <- arbitrary[GlobalSnapshotStateProof]
  } yield
    GlobalSnapshot(
      snapshotOrdinal,
      height,
      subHeight,
      lastSnapshotHash,
      SortedSet(blocks: _*),
      SortedMap.empty,
      SortedSet(reward),
      EpochProgress.MinValue,
      SortedSet.empty,
      SortedSet.empty,
      snapshotTips,
      stateProof
    )
  implicit val globalSnapshotArbitrary = Arbitrary(signedOf(arbitraryGlobalGen))

  test("snapshot is successfully deserialized and serialized with kryo") { implicit kryo =>
    forall { signedSnapshot: Signed[GlobalSnapshot] =>
      for {
        serializedBytes <- signedSnapshot.toBinaryF
        snapshotHash <- signedSnapshot.value.hashF
        deserializedSnapshot <- serializedBytes.fromBinaryF[Signed[GlobalSnapshot]]
        deserializedSnapshotHash <- deserializedSnapshot.value.hashF
      } yield expect.same(signedSnapshot, deserializedSnapshot).and(expect.same(snapshotHash, deserializedSnapshotHash))
    }
  }

  test("snapshot is successfully deserialized and serialized with json parser") {
    forall { signedSnapshot: Signed[GlobalSnapshot] =>
      for {
        serializedJson <- IO.delay(signedSnapshot.asJson)
        snapshotHash <- signedSnapshot.value.hashF
        deserializedSnapshot <- serializedJson.as[Signed[GlobalSnapshot]].leftWiden[Throwable].liftTo[IO]
        deserializedSnapshotHash <- deserializedSnapshot.value.hashF
      } yield expect.same(signedSnapshot, deserializedSnapshot).and(expect.same(snapshotHash, deserializedSnapshotHash))
    }
  }
}
