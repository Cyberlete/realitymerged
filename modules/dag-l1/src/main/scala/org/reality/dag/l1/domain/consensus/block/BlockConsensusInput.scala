package org.reality.dag.l1.domain.consensus.block

import cats.Show

import org.reality.dag.domain.block.Tips
import org.reality.dag.l1.domain.consensus.round.RoundId
import org.reality.dag.l1.{DataHashRequest, ExecutionRecord}
import org.reality.kernel.Ω
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction.{SwapTx, Transaction}
import org.reality.security.hash.{Hash, ProofsHash}
import org.reality.security.signature.Signed
import org.reality.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import org.web3j.protocol.core.methods.response.EthBlock

sealed trait BlockConsensusInput extends Ω

object BlockConsensusInput {
  sealed trait OwnerBlockConsensusInput extends BlockConsensusInput

  @derive(encoder, decoder)
  sealed trait PeerBlockConsensusInput extends BlockConsensusInput {
    val senderId: PeerId
    val owner: PeerId
  }

  // Existing cases
  case class OmegaEthBlockWrapper(ethBlock: EthBlock) extends BlockConsensusInput
  case class WasmOutputWrapper(output: ExecutionRecord) extends BlockConsensusInput
  case class DataHashWrapper(value: DataHashRequest) extends BlockConsensusInput
  case class SwapWrapper(swaps: List[SwapTx]) extends BlockConsensusInput
  case object OwnRoundTrigger extends OwnerBlockConsensusInput
  case object InspectionTrigger extends OwnerBlockConsensusInput

  case class Proposal(
    roundId: RoundId,
    senderId: PeerId,
    owner: PeerId,
    facilitators: Set[PeerId],
    transactions: Set[Signed[Transaction]],
    tips: Tips
  ) extends PeerBlockConsensusInput

  case class BlockSignatureProposal(roundId: RoundId, senderId: PeerId, owner: PeerId, signature: Signature) extends PeerBlockConsensusInput
  case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
      extends PeerBlockConsensusInput

  // added proofs into blocks
  @derive(encoder, decoder)
  case class ProofData(proofPath: String, metadataPath: String, commitment: Hash.Type, timestamp: Long)

  // added proofs into blocks
  @derive(encoder, decoder)
  case class ProofBlockWrapper(height: Long, hash: Hash.Type, proofs: List[ProofData], timestamp: Long, proofsHash: ProofsHash.Type)
      extends OwnerBlockConsensusInput

  implicit val showBlockConsensusInput: Show[BlockConsensusInput] = Show.show {
    case OwnRoundTrigger   => "OwnRoundTrigger"
    case InspectionTrigger => "InspectionTrigger"

    // added proofs into blocks
    case ProofBlockWrapper(height, hash, proofs, _, _) =>
      s"ProofBlockWrapper(height=$height, hash=${hash.toString.take(8)}, proofsCount=${proofs.size})"

    case Proposal(roundId, senderId, _, _, txs, _) =>
      s"Proposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)} txsCount=${txs.size})"
    case BlockSignatureProposal(roundId, senderId, _, _) =>
      s"BlockSignatureProposal(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)})"
    case CancelledBlockCreationRound(roundId, senderId, _, reason) =>
      s"CancelledBlockCreationRound(roundId=${roundId.value.toString.take(8)}, senderId=${senderId.value.value.take(8)}, reason=$reason)"
    case other => s"$other"
  }
}
