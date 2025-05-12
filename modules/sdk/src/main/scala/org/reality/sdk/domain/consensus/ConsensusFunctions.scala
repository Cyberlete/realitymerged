package org.reality.sdk.domain.consensus

import cats.data.NonEmptySet

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] {

  def extractNextValidators(context: Context): F[(Key, NonEmptySet[PeerId])]

  def triggerPredicate(event: Event): Boolean

  def validateArtifact(
    lastArtifact: Artifact,
    lastContext: Context,
    trigger: ConsensusTrigger,
    candidates: Set[PeerId],
    cycleHeightDiff: Long,
    facilitators: Set[PeerId],
    removed: Set[PeerId]
  )(
    artifact: Artifact
  ): F[Either[InvalidArtifact, (Artifact, Context)]]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Artifact,
    lastContext: Context,
    trigger: ConsensusTrigger,
    events: Set[Event],
    influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]],
    // TODO: some wrappers would help not making a mistake of putting wrong value in a wrong place
    newCandidates: Set[PeerId],
    facilitators: Set[PeerId],
    removed: Set[PeerId],
    maybeProposalBlocks: Option[SortedMap[PeerId, Signed[SortedSet[Hash]]]]
  ): F[(Artifact, Context, Set[Event], SortedSet[Hash])]

  def recalculateArtifactWithNewMetadata(
    lastKey: Key,
    lastArtifact: Artifact,
    lastContext: Context,
    trigger: ConsensusTrigger,
    facilitators: Set[PeerId],
    influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]],
    proposalBlocks: SortedMap[PeerId, Signed[SortedSet[Hash]]]
  )(artifact: Artifact): F[(Hash, Artifact, Context)]

  def consumeSignedMajorityArtifact(signedArtifact: Signed[Artifact], context: Context): F[Unit]

}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
