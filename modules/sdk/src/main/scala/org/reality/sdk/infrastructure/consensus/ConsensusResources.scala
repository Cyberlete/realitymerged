package org.reality.sdk.infrastructure.consensus

import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.reality.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Represents various data collected from other peers
  */
@derive(eqv, show)
case class ConsensusResources[A](
  peerDeclarationsMap: Map[PeerId, PeerDeclarations],
  acksMap: Map[(PeerId, PeerDeclarationKind), Set[PeerId]],
  withdrawalsMap: Map[PeerId, PeerDeclarationKind],
  ackKinds: Set[PeerDeclarationKind],
  artifacts: Map[Hash, A]
)

object ConsensusResources {
  def empty[A]: ConsensusResources[A] = ConsensusResources(Map.empty, Map.empty, Map.empty, Set.empty, Map.empty)
}
