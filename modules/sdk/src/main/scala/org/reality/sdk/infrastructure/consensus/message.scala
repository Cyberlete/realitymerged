package org.reality.sdk.infrastructure.consensus

import org.reality.sdk.infrastructure.consensus.declaration.PeerDeclaration
import org.reality.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object message {

  @derive(eqv, show, encoder, decoder)
  case class ConsensusEvent[E](value: E)

  @derive(eqv, show, encoder, decoder)
  case class ConsensusPeerDeclaration[K, D <: PeerDeclaration](key: K, declaration: D)

  @derive(eqv, show, encoder, decoder)
  case class ConsensusWithdrawPeerDeclaration[K](key: K, kind: PeerDeclarationKind)

  @derive(eqv, show, encoder, decoder)
  case class ConsensusArtifact[K, A](key: K, artifact: A)

  @derive(eqv, show, encoder, decoder)
  case class RegistrationResponse[Key](
    maybeKey: Option[Key]
  )

  @derive(eqv, show, encoder, decoder)
  case class GetConsensusOutcomeRequest[Key](
    key: Key
  )

}
