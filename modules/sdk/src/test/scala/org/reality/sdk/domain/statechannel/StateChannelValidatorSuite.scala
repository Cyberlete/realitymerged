package org.reality.sdk.domain.statechannel

import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.validated._

import org.reality.keytool.KeyPairGenerator
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.domain.statechannel.StateChannelValidator.{InvalidSigned, NotSignedExclusivelyByStateChannelOwner}
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.security.signature.SignedValidator
import org.reality.security.signature.SignedValidator.InvalidSignatures
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object StateChannelValidatorSuite extends MutableIOSuite {

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, StateChannelValidatorSuite.Res] =
    SecurityProvider.forAsync[IO]

  private val testStateChannel = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)

  test("should succeed when state channel is signed correctly") { implicit sp =>
    val validator = mkValidator()

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncJson(testStateChannel, keyPair)
      scOutput = StateChannelOutput(keyPair.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)

  }

  test("should fail when the signature is wrong") { implicit sp =>
    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncJson(testStateChannel, keyPair1).map(signed =>
        signed.copy(proofs = signed.proofs.map(_.copy(id = keyPair2.getPublic.toId)))
      )
      scOutput = StateChannelOutput(keyPair2.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
        result
      )
  }

  test("should fail when the signature doesn't match address") { implicit sp =>
    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncJson(testStateChannel, keyPair1)
      scOutput = StateChannelOutput(keyPair2.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  test("should fail when there is more than one signature") { implicit sp =>
    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncJson(testStateChannel, keyPair1)
      doubleSigned <- signedSCBinary.signAlsoWith(keyPair2)
      scOutput = StateChannelOutput(keyPair1.getPublic().toAddress, doubleSigned)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  private def mkValidator()(implicit S: SecurityProvider[IO]) =
    StateChannelValidator.make[IO](SignedValidator.make[IO])

}
