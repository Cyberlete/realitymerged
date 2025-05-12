package org.reality.dag.domain.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.reality.ext.cats.data.OrderBasedOrdering
import org.reality.ext.codecs.NonEmptySetCodec
import org.reality.schema._
import org.reality.schema.transaction.Transaction
import org.reality.security.Hashed
import org.reality.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Decoder

@derive(order, show, encoder, decoder)
case class NETBlockAsActiveTip(block: Signed[NETBlock], usageCount: NonNegLong) extends BlockAsActiveTip[NETBlock]

object NETBlockAsActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[NETBlockAsActiveTip]
}

@derive(show, eqv, encoder, decoder, order)
case class NETBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[Transaction]]
) extends Block[Transaction]

object NETBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[NETBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[Transaction]]] =
    NonEmptySetCodec.decoder[Signed[Transaction]]

  implicit class HashedOps(hashedBlock: Hashed[NETBlock]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }
}
