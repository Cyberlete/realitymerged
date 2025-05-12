package org.reality.dag.block.processing

import org.reality.dag.domain.block.NETBlock
import org.reality.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceState(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[NETBlock], NonNegLong)],
  rejected: List[(Signed[NETBlock], BlockRejectionReason)],
  awaiting: List[((Signed[NETBlock], TxChains), BlockAwaitReason)]
) {

  def toBlockAcceptanceResult: BlockAcceptanceResult =
    BlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object BlockAcceptanceState {

  def withRejectedBlocks(rejected: List[(Signed[NETBlock], BlockRejectionReason)]): BlockAcceptanceState =
    BlockAcceptanceState(
      contextUpdate = BlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
