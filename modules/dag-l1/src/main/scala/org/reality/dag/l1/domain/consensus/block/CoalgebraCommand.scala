package org.reality.dag.l1.domain.consensus.block

import org.reality.kernel.Ω
import org.reality.schema.transaction.Transaction

import BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, OmegaEthBlockWrapper, Proposal, SwapWrapper}

sealed trait CoalgebraCommand extends Ω

object CoalgebraCommand extends Ω {
  case object StartOwnRound extends CoalgebraCommand
  case class StartOwnRoundWith(txs: List[Transaction]) extends CoalgebraCommand
  case object InspectConsensuses extends CoalgebraCommand
  case class ProcessProposal(proposal: Proposal) extends CoalgebraCommand
  case class ProcessBlockSignatureProposal(blockSignatureProposal: BlockSignatureProposal) extends CoalgebraCommand
  case class ProcessCancellation(cancellation: CancelledBlockCreationRound) extends CoalgebraCommand
  case class Empty() extends CoalgebraCommand
  case class EnqueueETHBlock(data: OmegaEthBlockWrapper) extends CoalgebraCommand
  case class EnqueueETHSwaps(swapWrapper: SwapWrapper) extends CoalgebraCommand

}
