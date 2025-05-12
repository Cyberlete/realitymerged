package org.reality.dag.transaction

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import org.reality.ext.cats.syntax.validated._
import org.reality.schema.address.Address
import org.reality.schema.transaction.{StandardTransaction, Transaction}
import org.reality.security.signature.SignedValidator.SignedValidationError
import org.reality.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

import TransactionValidator.TransactionValidationErrorOr

trait TransactionValidator[F[_]] {

  def validate(signedTransaction: Signed[Transaction]): F[TransactionValidationErrorOr[Signed[Transaction]]]

}

object TransactionValidator {

  val lockedAddresses: Set[Address] = Set.empty

  def make[F[_]: Async](
    signedValidator: SignedValidator[F]
  ): TransactionValidator[F] =
    new TransactionValidator[F] {

      def validate(
        signedTransaction: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] =
        for {
          signaturesV <- signedValidator
            .validateSignatures(signedTransaction)
            .map(_.errorMap[TransactionValidationError](InvalidSigned))
          srcAddressSignatureV <- validateSourceAddressSignature(signedTransaction)
          amountV = validateExistanceOfAmount(signedTransaction)
          differentSrcAndDstV = validateDifferentSourceAndDestinationAddress(signedTransaction)
        } yield
          signaturesV
            .productR(srcAddressSignatureV)
            .productR(amountV)
            .productR(differentSrcAndDstV)

      private def validateSourceAddressSignature(
        signedTx: Signed[Transaction]
      ): F[TransactionValidationErrorOr[Signed[Transaction]]] =
        signedValidator
          .isSignedExclusivelyBy(signedTx, signedTx.source)
          .map(_.errorMap[TransactionValidationError](_ => NotSignedBySourceAddressOwner))

      private def validateDifferentSourceAndDestinationAddress(
        signedTx: Signed[Transaction]
      ): TransactionValidationErrorOr[Signed[Transaction]] =
        if (signedTx.source =!= signedTx.destination)
          signedTx.validNec[TransactionValidationError]
        else
          SameSourceAndDestinationAddress(signedTx.source).invalidNec[Signed[Transaction]]

      private def validateExistanceOfAmount(signedTx: Signed[Transaction]) =
        signedTx.value match {
          case StandardTransaction(_, _, amount, _, _, _) if amount.value != NonNegLong.MinValue =>
            signedTx.validNec[TransactionValidationError]
          case StandardTransaction(_, _, _, _, _, _)          => StandardTransactionAmountZero.invalidNec[Signed[Transaction]]
          case txn if txn.amount.value == NonNegLong.MinValue => signedTx.validNec[TransactionValidationError]
          case _                                              => NonStandardTransactionAmountNotZero.invalidNec[Signed[Transaction]]
        }
    }

  @derive(eqv, show)
  sealed trait TransactionValidationError
  case class InvalidSigned(error: SignedValidationError) extends TransactionValidationError
  case object NotSignedBySourceAddressOwner extends TransactionValidationError
  case class SameSourceAndDestinationAddress(address: Address) extends TransactionValidationError
  case object StandardTransactionAmountZero extends TransactionValidationError
  case object NonStandardTransactionAmountNotZero extends TransactionValidationError

  type TransactionValidationErrorOr[A] = ValidatedNec[TransactionValidationError, A]
}
