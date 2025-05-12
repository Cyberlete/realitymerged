package org.reality.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import org.reality.dag.domain.block.NETBlock
import org.reality.schema.gossip.CommonRumor
import org.reality.sdk.infrastructure.gossip.RumorHandler
import org.reality.security.signature.Signed

object handler {

  def blockRumorHandler[F[_]: Async](
    peerBlockQueue: Queue[F, Signed[NETBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[NETBlock]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
