package org.reality.sdk.infrastructure.consensus

import cats.Show
import cats.syntax.option._
import cats.syntax.show._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.consensus.declaration.kind._
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv)
case class ConsensusState[Key, Artifact, Context](
  selfId: PeerId, // Added only to have better show for consensus status, can be probably achieved in a better way
  key: Key,
  facilitators: List[PeerId], // TODO: why not a Set or SortedSet?
  status: ConsensusStatus[Artifact, Context],
  createdAt: FiniteDuration,
  removedFacilitators: Set[PeerId] = Set.empty,
  withdrawnFacilitators: Set[PeerId] = Set.empty
)

object ConsensusState {
  implicit def showInstance[K: Show, A, C]: Show[ConsensusState[K, A, C]] = { cs =>
    implicit val statusShow = ConsensusStatus.showInstance[A, C](cs.facilitators.contains(cs.selfId))

    s"""ConsensusState{
       |key=${cs.key.show},
       |facilitatorCount=${cs.facilitators.size.show},
       |removedFacilitators=${cs.removedFacilitators.show},
       |withdrawnFacilitators=${cs.withdrawnFacilitators.show},
       |status=${cs.status.show}
       |}""".stripMargin.replace(",\n", ", ")
  }

  implicit class ConsensusStateOps[K, A, C](value: ConsensusState[K, A, C]) {
    private val kindRelation: (Option[PeerDeclarationKind], Set[PeerDeclarationKind]) = value.status match {
      case _: CollectingFacilities[A, C] => (Facility.some, Set.empty)
      case _: CollectingProposals[A, C]  => (Proposal.some, Set(Facility))
      case _: CollectingSignatures[A, C] => (MajoritySignature.some, Set(Facility, Proposal))
      case _: Finished[A, C]             => (none, Set(Facility, Proposal, MajoritySignature))
    }

    def maybeCollectingKind: Option[PeerDeclarationKind] = kindRelation._1
  }
}

@derive(eqv)
sealed trait ConsensusStatus[Artifact, Context]

final case class CollectingFacilities[A, C](facilitatorsHash: Hash, signedTrustMap: Signed[SortedMap[PeerId, Double]])
    extends ConsensusStatus[A, C]

final case class CollectingProposals[A, C](
  majorityTrigger: ConsensusTrigger,
  maybeProposalInfo: Option[ProposalInfo[A, C]],
  candidates: Set[PeerId],
  facilitators: Set[PeerId],
  removed: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]

final case class CollectingSignatures[A, C](
  majorityArtifactInfo: ProposalInfo[A, C],
  majorityTrigger: ConsensusTrigger,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]

@derive(encoder, decoder)
final case class Finished[A, C](
  signedMajorityArtifact: Signed[A],
  context: C,
  majorityTrigger: ConsensusTrigger,
  artifactHash: Hash,
  entropyRates: Map[PeerId, Double]
) extends ConsensusStatus[A, C]

object ConsensusStatus {
  def showInstance[A, C](isFacilitator: Boolean): Show[ConsensusStatus[A, C]] = { status =>
    val prefix = if (isFacilitator) "" else "Following"

    status match {
      case CollectingFacilities(facilitatorsHash, _) =>
        s"${prefix}CollectingFacilities{}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingProposals(majorityTrigger, proposalInfo, candidates, facilitators, removed, facilitatorsHash) =>
        s"${prefix}CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalInfo=${proposalInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingSignatures(majorityArtifactHash, majorityTrigger, candidates, facilitatorsHash) =>
        s"${prefix}CollectingSignatures{majorityArtifactHash=${majorityArtifactHash.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case Finished(_, _, majorityTrigger, facilitatorsHash, entropyRates) =>
        s"${prefix}Finished{majorityTrigger=${majorityTrigger.show}, facilitatorsHash=${facilitatorsHash.show}, entropyRates=${entropyRates.show}}"
    }
  }
}

@derive(eqv)
case class ProposalInfo[A, C](
  proposalArtifact: A,
  context: C,
  artifactHash: Hash,
  entropyRates: Map[PeerId, Double]
)
object ProposalInfo {
  implicit def showInstance[A, C]: Show[ProposalInfo[A, C]] = pi => s"ProposalInfo{artifactHash=${pi.artifactHash.show}}"
}
