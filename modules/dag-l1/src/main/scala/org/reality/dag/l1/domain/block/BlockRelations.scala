package org.reality.dag.l1.domain.block

import cats.effect.kernel.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.reality.dag.domain.block.NETBlock
import org.reality.schema.BlockReference
import org.reality.schema.transaction.TransactionReference
import org.reality.security.Hashed
import org.reality.security.signature.Signed

object BlockRelations {

  def dependsOn[F[_]: Async](
    blocks: Hashed[NETBlock]
  )(block: Signed[NETBlock]): F[Boolean] = dependsOn(Set(blocks))(block)

  def dependsOn[F[_]: Async](
    blocks: Set[Hashed[NETBlock]],
    references: Set[BlockReference] = Set.empty
  )(block: Signed[NETBlock]): F[Boolean] = {
    def dstAddresses = blocks.flatMap(_.transactions.toSortedSet.toList.map(_.value.destination))

    def isChild =
      block.parent.exists(parentRef => (blocks.map(_.ownReference) ++ references).exists(_ === parentRef))
    def hasReferencedAddress = block.transactions.map(_.source).exists(srcAddress => dstAddresses.exists(_ === srcAddress))
    def hasReferencedTx = blocks.toList
      .flatTraverse(_.transactions.toSortedSet.toList.traverse(TransactionReference.of(_)))
      .map(_.toSet)
      .map { txRefs =>
        block.transactions.map(_.parent).exists(txnParentRef => txRefs.exists(_ === txnParentRef))
      }

    if (isChild || hasReferencedAddress) true.pure[F] else hasReferencedTx
  }
}
