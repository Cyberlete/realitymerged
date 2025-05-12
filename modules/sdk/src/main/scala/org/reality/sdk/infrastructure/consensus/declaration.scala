package org.reality.sdk.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary

object declaration {

  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(
    upperBound: Bound,
    newCandidates: Set[PeerId],
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    trustMapSignature: Signature
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(
    hash: Hash,
    facilitatorsHash: Hash,
    blocks: Signed[SortedSet[Hash]],
    peerId: PeerId, // TODO: this value should be validated
    signedTrustMap: Signed[SortedMap[PeerId, Double]],
    height: Long = 0L
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(signature: Signature, facilitatorsHash: Hash) extends PeerDeclaration

  object kind {

    @derive(arbitrary, eqv, show, encoder, decoder)
    sealed trait PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Facility extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Proposal extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object MajoritySignature extends PeerDeclarationKind

  }

}
