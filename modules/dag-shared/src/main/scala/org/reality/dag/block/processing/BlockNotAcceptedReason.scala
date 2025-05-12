package org.reality.dag.block.processing

import cats.data.NonEmptyList

import org.reality.dag.block.BlockValidator.BlockValidationError
import org.reality.schema.BlockReference
import org.reality.schema.address.Address
import org.reality.schema.balance.BalanceArithmeticError
import org.reality.schema.transaction.{TransactionOrdinal, TransactionReference}
import org.reality.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait BlockNotAcceptedReason

@derive(eqv, show)
sealed trait BlockRejectionReason extends BlockNotAcceptedReason
case class ValidationFailed(reasons: NonEmptyList[BlockValidationError]) extends BlockRejectionReason
case class ParentNotFound(parent: BlockReference) extends BlockRejectionReason
case class RejectedTransaction(tx: TransactionReference, reason: TransactionRejectionReason) extends BlockRejectionReason

@derive(eqv, show)
sealed trait BlockAwaitReason extends BlockNotAcceptedReason
case class AwaitingTransaction(tx: TransactionReference, reason: TransactionAwaitReason) extends BlockAwaitReason
case class AddressBalanceOutOfRange(address: Address, error: BalanceArithmeticError) extends BlockAwaitReason
case class SigningPeerBelowCollateral(peerIds: NonEmptyList[Address]) extends BlockAwaitReason

@derive(eqv, show)
sealed trait TransactionAwaitReason
case class ParentOrdinalAboveLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
    extends TransactionAwaitReason

@derive(eqv, show)
sealed trait TransactionRejectionReason
case class ParentHashNotEqLastTxHash(parentHash: Hash, lastTxHash: Hash) extends TransactionRejectionReason
case class ParentOrdinalBelowLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
    extends TransactionRejectionReason
