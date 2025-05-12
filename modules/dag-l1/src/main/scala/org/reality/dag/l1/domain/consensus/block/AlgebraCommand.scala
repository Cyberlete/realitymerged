package org.reality.dag.l1.domain.consensus.block

import org.reality.kernel.Ω
import org.reality.schema.transaction.SwapTx

import BlockConsensusInput.{BlockSignatureProposal, CancelledBlockCreationRound, OmegaEthBlockWrapper, Proposal}

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class PersistInitialOwnRoundData(roundData: RoundData) extends AlgebraCommand
  case class PersistInitialPeerRoundData(roundData: RoundData, peerProposal: Proposal) extends AlgebraCommand
  case class PersistProposal(proposal: Proposal) extends AlgebraCommand
  case class PersistBlockSignatureProposal(blockSignatureProposal: BlockSignatureProposal) extends AlgebraCommand
  case class InformAboutInabilityToParticipate(proposal: Proposal, reason: CancellationReason) extends AlgebraCommand
  case class PersistCancellationResult(cancellation: CancelledBlockCreationRound) extends AlgebraCommand
  case class InformAboutRoundStartFailure(message: String) extends AlgebraCommand
  case class CancelTimedOutRounds(toCancel: Set[Proposal]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
  case class ProcessSwaps(swaps: List[SwapTx], blocks: List[OmegaEthBlockWrapper]) extends AlgebraCommand
}
