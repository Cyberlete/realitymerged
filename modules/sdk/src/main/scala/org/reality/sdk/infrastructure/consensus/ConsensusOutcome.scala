package org.reality.sdk.infrastructure.consensus

import scala.concurrent.duration.FiniteDuration

import org.reality.ext.codecs.FiniteDurationCodec.{decoder => decodr, encoder => encodr}
import org.reality.schema.peer.PeerId

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Represents a finished consensus
  */
@derive(eqv, encoder, decoder)
case class ConsensusOutcome[Key, Artifact, Context](
  key: Key,
  facilitators: List[PeerId],
  // TODO: what about a situation  when we are sending the outcome to another node for it to start from that outcome
  consensusStartedAt: FiniteDuration,
  status: Finished[Artifact, Context]
) {
  def toState(selfId: PeerId): ConsensusState[Key, Artifact, Context] = ConsensusState(
    selfId,
    key,
    facilitators,
    status,
    consensusStartedAt
  )
}
