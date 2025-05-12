package org.reality.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.reality.dag.domain.block.NETBlock
import org.reality.sdk.domain.Daemon
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.infrastructure.consensus.message.ConsensusEvent
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

import fs2.Stream
import io.circe.disjunctionCodecs._

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    l1OutputQueue: Queue[F, Signed[NETBlock]],
    gossip: Gossip[F]
  ): Daemon[F] = Daemon.spawn {
    Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .map(_.asLeft[NETEvent])
      .merge(
        Stream
          .fromQueueUnterminated(l1OutputQueue)
          .map(_.asRight[StateChannelEvent])
      )
      .map(ConsensusEvent(_))
      .evalMap(gossip.spread[ConsensusEvent[GlobalSnapshotEvent]])
      .compile
      .drain
  }

}
