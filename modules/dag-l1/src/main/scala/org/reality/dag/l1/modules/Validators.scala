package org.reality.dag.l1.modules

import cats.effect.Async

import org.reality.dag.block.BlockValidator
import org.reality.dag.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.reality.schema.address.Address
import org.reality.sdk.app.SDK
import org.reality.sdk.infrastructure.gossip.RumorValidator
import org.reality.security.SecurityProvider
import org.reality.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: SecurityProvider](
    sdk: SDK[F],
    storages: L1Storages[F]
//    seedlist: Option[Set[PeerId]]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator =
      BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val contextualTransactionValidator = ContextualTransactionValidator.make[F](
      transactionValidator,
      (address: Address) => storages.transaction.getLastAcceptedReference(address)
    )
    val rumorValidator = RumorValidator.make[F](sdk.seedlist, signedValidator)

    new Validators[F](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F],
  val transaction: TransactionValidator[F],
  val transactionContextual: ContextualTransactionValidator[F],
  val rumorValidator: RumorValidator[F]
)
