package org.reality.sdk.modules

import cats.effect.Async

import org.reality.dag.block.BlockValidator
import org.reality.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.infrastructure.gossip.RumorValidator
import org.reality.security.SecurityProvider
import org.reality.security.signature.SignedValidator

object SdkValidators {

  def make[F[_]: Async: SecurityProvider](
    seedlist: Option[Set[PeerId]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val stateChannelValidator = StateChannelValidator.make[F](signedValidator)

    new SdkValidators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator,
      rumorValidator,
      stateChannelValidator
    ) {}
  }
}

sealed abstract class SdkValidators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
