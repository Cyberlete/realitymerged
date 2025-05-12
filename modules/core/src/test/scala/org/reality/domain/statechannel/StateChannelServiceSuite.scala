package org.reality.domain.statechannel

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._
import org.reality.domain.cell.L0Cell
import org.reality.keytool.KeyPairGenerator
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import org.reality.dag.domain.block.NETBlock
import weaver.MutableIOSuite

object StateChannelServiceSuite extends MutableIOSuite {

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, StateChannelServiceSuite.Res] =
    SecurityProvider.forAsync[IO]

  test("state channel output processed successfully") { implicit sp =>
    for {
      output <- mkStateChannelOutput()
      service <- mkService()
      result <- service.process(output)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { implicit sp =>
    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(Some(expected))
      result <- service.process(output)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.fold[StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))
    }

    for {
      netQueue <- Queue.unbounded[IO, Signed[NETBlock]]
      scQueue <- Queue.unbounded[IO, StateChannelOutput]
    } yield StateChannelService.make[IO](L0Cell.mkL0Cell[IO](netQueue, scQueue), validator)
  }

  def mkStateChannelOutput()(implicit S: SecurityProvider[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
    signedSC <- forAsyncJson(binary, keyPair)

  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
