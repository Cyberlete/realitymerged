package org.reality.net.transaction

import java.security.KeyPair
import cats.data.Validated.Valid
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.validated._
import org.reality.net.transaction.ContextualTransactionValidator._
import org.reality.dag.transaction.TransactionValidator.{InvalidSigned, NotSignedBySourceAddressOwner, SameSourceAndDestinationAddress}
import org.reality.ext.cats.effect._
import org.reality.keytool.KeyPairGenerator
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.security.signature.SignedValidator.InvalidSignatures
import org.reality.security.signature.{Signed, SignedValidator}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.reality.dag.transaction.TransactionValidator
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object ContextualTransactionValidatorSuite extends ResourceSuite with Checkers {
  override type Res = (
    (Address => TransactionReference) => ContextualTransactionValidator[IO],
    KeyPair,
    KeyPair,
    Address,
    Address,
    StandardTransaction,
    (Transaction, KeyPair) => IO[Signed[Transaction]],
    (Signed[Transaction], KeyPair) => IO[Signed[Transaction]]
  )

  override def sharedResource: Resource[IO, Res] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      def txValidator(
        lastAcceptedTxFn: Address => TransactionReference
      ): ContextualTransactionValidator[IO] = {
        val signedValidator = SignedValidator.make
        val txValidator = TransactionValidator.make(signedValidator)

        ContextualTransactionValidator.make[IO](
          txValidator,
          (address: Address) => lastAcceptedTxFn(address).pure[IO]
        )
      }

      def signTx(tx: Transaction, keyPair: KeyPair) = forAsyncJson(tx, keyPair)
      def nextSignTx(tx: Signed[Transaction], keyPair: KeyPair) = tx.signAlsoWith(keyPair)

      (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).mapN {
        case (srcKey, dstKey) =>
          val src = srcKey.getPublic.toAddress
          val dst = dstKey.getPublic.toAddress

          val tx = StandardTransaction(
            src,
            dst,
            TransactionAmount(PosLong(1L)),
            TransactionFee(NonNegLong(0L)),
            TransactionReference.empty,
            TransactionSalt(0L)
          )

          (srcKey, dstKey, src, dst, tx)
      }.asResource.map {
        case (srcKey, dstKey, src, dst, tx) =>
          (txValidator, srcKey, dstKey, src, dst, tx, signTx, nextSignTx)
      }
    }

  val initialReference: Address => TransactionReference = _ => TransactionReference.empty

  def setReference(hash: Hash) =
    StandardTransaction._ParentHash.replace(hash).andThen(StandardTransaction._ParentOrdinal.replace(TransactionOrdinal(1L)))

  test("should succeed when all values are correct") {
    case (txValidator, srcKey, _, _, _, tx, signTx, _) =>
      val validator = txValidator(initialReference)

      for {
        signedTx <- signTx(tx, srcKey)
        result <- validator.validate(signedTx)
      } yield expect.same(result, Valid(signedTx))
  }

  test("should succeed when lastTxRef is greater than lastTxRef stored on the node") {
    case (txValidator, srcKey, _, _, _, baseTx, signTx, _) =>
      val validator = txValidator(initialReference)

      val tx = setReference(Hash("someHash"))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield expect.same(Valid(signedTx), validationResult)
  }

  test("should fail when lastTxRef's ordinal is lower than one stored on the node") {
    case (txValidator, srcKey, _, _, _, tx, signTx, _) =>
      val reference =
        (_: Address) => TransactionReference(TransactionOrdinal(NonNegLong(1L)), Hash("someHash"))
      val validator = txValidator(reference)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield
        expect.same(
          ParentOrdinalLowerThenLastTxOrdinal(TransactionOrdinal(0L), TransactionOrdinal(1L)).invalidNec,
          validationResult
        )
  }

  test("should fail when lastTxRef's ordinal matches but the hash is different") {
    case (txValidator, srcKey, _, _, _, baseTx, signTx, _) =>
      val reference =
        (_: Address) => TransactionReference(TransactionOrdinal(NonNegLong(1L)), Hash("someHash"))
      val validator = txValidator(reference)

      val tx = setReference(Hash("someOtherHash"))(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield
        expect.same(
          ParentHashDifferentThanLastTxHash(Hash("someOtherHash"), Hash("someHash")).invalidNec,
          validationResult
        )
  }

  test("should fail when source address doesn't match signer id") {
    case (txValidator, _, dstKey, _, _, tx, signTx, _) =>
      val validator = txValidator(initialReference)

      for {
        signedTx <- signTx(tx, dstKey)
        validationResult <- validator.validate(signedTx)
      } yield
        expect.same(
          NonContextualValidationError(NotSignedBySourceAddressOwner).invalidNec,
          validationResult
        )
  }

  test("should fail when source address is not the only signer") {
    case (txValidator, srcKey, dstKey, _, _, tx, signTx, nextSignTx) =>
      val validator = txValidator(initialReference)

      for {
        signedTx <- signTx(tx, srcKey)
        doubleSignedTx <- nextSignTx(signedTx, dstKey)
        validationResult <- validator.validate(doubleSignedTx)
      } yield
        expect.same(
          NonContextualValidationError(NotSignedBySourceAddressOwner).invalidNec,
          validationResult
        )
  }

  test("should fail when the signature is wrong") {
    case (txValidator, srcKey, dstKey, _, _, tx, signTx, _) =>
      val validator = txValidator(initialReference)

      for {
        signedTx <- signTx(tx, dstKey).map(signed => signed.copy(proofs = signed.proofs.map(_.copy(id = srcKey.getPublic.toId))))
        validationResult <- validator.validate(signedTx)
      } yield
        expect.same(
          NonContextualValidationError(InvalidSigned(InvalidSignatures(signedTx.proofs))).invalidNec,
          validationResult
        )
  }

  test("should fail when source address is the same as destination address") {
    case (txValidator, srcKey, _, srcAddress, _, baseTx, signTx, _) =>
      val validator = txValidator(initialReference)

      val tx = StandardTransaction._Destination.replace(srcAddress)(baseTx)

      for {
        signedTx <- signTx(tx, srcKey)
        validationResult <- validator.validate(signedTx)
      } yield
        expect.same(
          NonContextualValidationError(SameSourceAndDestinationAddress(srcAddress)).invalidNec,
          validationResult
        )
  }

}
