package org.reality.dag.block

import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.validated._
import cats.{Functor, Order}

import org.reality.dag.domain.block.NETBlock
import org.reality.dag.transaction.TransactionChainValidator.{TransactionChainBroken, TransactionNel}
import org.reality.dag.transaction.TransactionValidator.TransactionValidationError
import org.reality.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.reality.ext.cats.syntax.validated._
import org.reality.schema.BlockReference
import org.reality.schema.address.Address
import org.reality.schema.transaction.TransactionReference
import org.reality.security.signature.SignedValidator.SignedValidationError
import org.reality.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

import BlockValidator.{BlockValidationErrorOr, BlockValidationParams}

trait BlockValidator[F[_]] {

  def validate(
    signedBlock: Signed[NETBlock],
    params: BlockValidationParams = BlockValidationParams.default
  ): F[BlockValidationErrorOr[(Signed[NETBlock], Map[Address, TransactionNel])]]

  def validateGetBlock(
    signedBlock: Signed[NETBlock],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Signed[NETBlock]]] =
    validate(signedBlock, params).map(_.map(_._1))

  def validateGetTxChains(
    signedBlock: Signed[NETBlock],
    params: BlockValidationParams = BlockValidationParams.default
  )(implicit ev: Functor[F]): F[BlockValidationErrorOr[Map[Address, TransactionNel]]] =
    validate(signedBlock, params).map(_.map(_._2))

}

object BlockValidator {

  def make[F[_]: Async](
    signedValidator: SignedValidator[F],
    transactionChainValidator: TransactionChainValidator[F],
    transactionValidator: TransactionValidator[F]
  ): BlockValidator[F] =
    new BlockValidator[F] {

      def validate(
        signedBlock: Signed[NETBlock],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[(Signed[NETBlock], Map[Address, TransactionNel])]] =
        for {
          signedV <- validateSigned(signedBlock, params)
          transactionsV <- validateTransactions(signedBlock)
          propertiesV = validateProperties(signedBlock, params)
          transactionChainV <- validateTransactionChain(signedBlock)
        } yield
          signedV
            .productR(transactionsV)
            .productR(propertiesV)
            .product(transactionChainV)

      private def validateSigned(
        signedBlock: Signed[NETBlock],
        params: BlockValidationParams
      ): F[BlockValidationErrorOr[Signed[NETBlock]]] =
        signedValidator
          .validateSignatures(signedBlock)
          .map { signaturesV =>
            signaturesV
              .productR(signedValidator.validateUniqueSigners(signedBlock))
              .productR(signedValidator.validateMinSignatureCount(signedBlock, params.minSignatureCount))
          }
          .map(_.errorMap[BlockValidationError](InvalidSigned))

      private def validateTransactions(
        signedBlock: Signed[NETBlock]
      ): F[BlockValidationErrorOr[Signed[NETBlock]]] =
        signedBlock.value.transactions.toNonEmptyList.traverse { signedTransaction =>
          for {
            txRef <- TransactionReference.of(signedTransaction)
            txV <- transactionValidator.validate(signedTransaction)
          } yield txV.errorMap(InvalidTransaction(txRef, _))
        }.map { vs =>
          vs.foldLeft(signedBlock.validNec[BlockValidationError]) { (acc, v) =>
            acc.productL(v)
          }
        }

      private def validateTransactionChain(
        signedBlock: Signed[NETBlock]
      ): F[BlockValidationErrorOr[Map[Address, TransactionNel]]] =
        transactionChainValidator
          .validate(signedBlock.transactions)
          .map(_.errorMap[BlockValidationError](InvalidTransactionChain))

      private def validateProperties(
        signedBlock: Signed[NETBlock],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[NETBlock]] =
        validateParentCount(signedBlock, params)
          .productR(validateUniqueParents(signedBlock))

      private def validateParentCount(
        signedBlock: Signed[NETBlock],
        params: BlockValidationParams
      ): BlockValidationErrorOr[Signed[NETBlock]] =
        if (signedBlock.parent.size >= params.minParentCount)
          signedBlock.validNec
        else
          NotEnoughParents(signedBlock.parent.size, params.minParentCount).invalidNec

      private def validateUniqueParents(
        signedBlock: Signed[NETBlock]
      ): BlockValidationErrorOr[Signed[NETBlock]] =
        duplicatedValues(signedBlock.parent).toNel
          .map(NonUniqueParents)
          .toInvalidNec(signedBlock)

      private def duplicatedValues[A: Order](values: NonEmptyList[A]): List[A] =
        values.groupBy(identity).toList.mapFilter {
          case (value, occurrences) =>
            if (occurrences.tail.nonEmpty)
              value.some
            else
              none
        }
    }

  case class BlockValidationParams(minSignatureCount: PosInt, minParentCount: PosInt)

  object BlockValidationParams {
    val default: BlockValidationParams = BlockValidationParams(minSignatureCount = 3, minParentCount = 2)
  }

  @derive(eqv, show)
  sealed trait BlockValidationError
  case class InvalidTransactionChain(error: TransactionChainBroken) extends BlockValidationError
  case class InvalidTransaction(transactionReference: TransactionReference, error: TransactionValidationError) extends BlockValidationError
  case class InvalidSigned(error: SignedValidationError) extends BlockValidationError
  case class NotEnoughParents(parentCount: Int, minParentCount: Int) extends BlockValidationError
  case class NonUniqueParents(duplicatedParents: NonEmptyList[BlockReference]) extends BlockValidationError

  type BlockValidationErrorOr[A] = ValidatedNec[BlockValidationError, A]
}
