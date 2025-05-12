package org.reality.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.validated._

import scala.collection.immutable.{SortedMap, SortedSet}
import org.reality.ext.crypto._
import org.reality.keytool.KeyPairGenerator
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import org.reality.dag.snapshot.GlobalSnapshotInfo
import weaver.MutableIOSuite

object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, GlobalSnapshotStateChannelEventsProcessorSuite.Res] =
    SecurityProvider.forAsync[IO]

  val ordinal20 = SnapshotOrdinal(20L)

  def mkProcessor(failed: Option[(Address, StateChannelValidator.StateChannelValidationError)] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
    }
    GlobalSnapshotStateChannelEventsProcessor.make[IO](validator)
  }

  test("return new sc event") { implicit sp =>
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair.getPublic)
      address = keyPair.getPublic().toAddress
      output <- mkStateChannelOutput(keyPair)
      snapshotInfo = mkGlobalSnapshotInfo(nextRotationOrdinal = ordinal20, peerId = pId)
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.one(output.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output :: Nil)
    } yield expect.same(expected, result)

  }

  test("return two dependent sc events") { implicit sp =>
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair.getPublic)
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshot.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo(nextRotationOrdinal = ordinal20, peerId = pId)
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.of(output2.snapshot, output1.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc event when reference to last state channel snapshot hash is correct") { implicit sp =>
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair.getPublic)
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshot.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((address, output1Hash)), nextRotationOrdinal = ordinal20, peerId = pId)
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.of(output2.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return no sc events when reference to last state channel snapshot hash is incorrect") { implicit sp =>
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair.getPublic)
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes("incorrect".getBytes())))
      snapshotInfo = mkGlobalSnapshotInfo(
        SortedMap((address, Hash.fromBytes(output1.snapshot.content))),
        nextRotationOrdinal = ordinal20,
        peerId = pId
      )
      service = mkProcessor()
      expected = (SortedMap.empty[Address, NonEmptyList[StateChannelSnapshotBinary]], Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc events for different addresses") { implicit sp =>
    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair1.getPublic)
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo(nextRotationOrdinal = ordinal20, peerId = pId)
      service = mkProcessor()
      expected = (
        SortedMap((address1, NonEmptyList.of(output1.snapshot)), (address2, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return only valid sc events") { implicit sp =>
    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      pId = PeerId.fromPublic(keyPair1.getPublic)
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo(nextRotationOrdinal = ordinal20, peerId = pId)
      service = mkProcessor(Some(address1 -> StateChannelValidator.NotSignedExclusivelyByStateChannelOwner))
      expected = (
        SortedMap((address2, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  def mkStateChannelOutput(keyPair: KeyPair, hash: Option[Hash] = None)(implicit S: SecurityProvider[IO]) = for {
    content <- Random.scalaUtilRandom[IO].flatMap(_.nextString(10))
    binary <- StateChannelSnapshotBinary(hash.getOrElse(Hash.empty), content.getBytes).pure[IO]
    signedSC <- forAsyncJson(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkGlobalSnapshotInfo(
    lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty,
    nextRotationOrdinal: SnapshotOrdinal,
    peerId: PeerId
  ) =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedSet.empty,
      (nextRotationOrdinal, NonEmptySet.one(peerId))
    )

}
