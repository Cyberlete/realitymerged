package org.reality.net.block

import java.security.KeyPair
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{Async, IO, Resource}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.validated._

import scala.collection.immutable.SortedSet
import org.reality.dag.block.BlockValidator.BlockValidationError
import org.reality.ext.crypto._
import org.reality.keytool.KeyPairGenerator
import org.reality.schema.BlockReference
import org.reality.schema.height.Height
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.hash.ProofsHash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.{Signed, SignedValidator}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import org.reality.dag.block.BlockValidator
import org.reality.dag.domain.block.NETBlock
import org.reality.dag.transaction.{TransactionChainValidator, TransactionValidator}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockValidatorSuite extends MutableIOSuite with Checkers {
  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, BlockValidatorSuite.Res] =
    SecurityProvider.forAsync[IO]

  private def makeValidator[G[_]: Async: SecurityProvider]: BlockValidator[G] = {
    val signedValidator = SignedValidator.make[G]
    val transactionChainValidator = TransactionChainValidator.make[G]
    val transactionValidator = TransactionValidator.make[G](signedValidator)
    BlockValidator.make[G](signedValidator, transactionChainValidator, transactionValidator)
  }

  private def generateKeys[G[_]: Async: SecurityProvider](count: PosInt): G[NonEmptyList[KeyPair]] =
    for {
      head <- KeyPairGenerator.makeKeyPair[G]
      tail <- KeyPairGenerator.makeKeyPair[G].replicateA(count - 1)
    } yield NonEmptyList.of(head, tail: _*)

  test("validation should pass for valid block") { implicit sp =>
    val validator = makeValidator[IO]

    for {
      keys <- generateKeys[IO](3)
      src = keys.head.getPublic.toAddress
      dst = keys.toList(1).getPublic.toAddress
      tx <- Signed
        .forAsyncJson[IO, Transaction](
          StandardTransaction(
            src,
            dst,
            TransactionAmount(1L),
            TransactionFee(0L),
            TransactionReference.empty,
            TransactionSalt(0L)
          ),
          keys.head
        )
      block = NETBlock(
        NonEmptyList.of(
          BlockReference(Height(10L), ProofsHash("parent1")),
          BlockReference(Height(12L), ProofsHash("parent2"))
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(tx))
      )
      signedBlock <- block.sign(keys)
      validated <- validator.validateGetBlock(signedBlock)
    } yield expect.same(validated, signedBlock.validNec[BlockValidationError])
  }

}
