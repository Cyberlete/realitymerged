package org.reality.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.domain.block.NETBlock
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.reality.modules.Queues
import org.reality.schema.gossip.RumorRaw
import org.reality.sdk.app.SDK
import org.reality.security.Hashed
import org.reality.security.signature.Signed
object L1Queues {

  def make[F[_]: Concurrent](sdk: SDK[F]): F[L1Queues[F]] =
    for {
      peerBlockConsensusInputQueue <- Queue.unbounded[F, Signed[PeerBlockConsensusInput]]
      peerBlockQueue <- Queue.unbounded[F, Signed[NETBlock]]
    } yield
      new L1Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sdk.sdkQueues.rumor
        val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]] = peerBlockConsensusInputQueue
        val peerBlock: Queue[F, Signed[NETBlock]] = peerBlockQueue
      }
}

abstract class L1Queues[F[_]] extends Queues[F] {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val peerBlockConsensusInput: Queue[F, Signed[PeerBlockConsensusInput]]
  val peerBlock: Queue[F, Signed[NETBlock]]
}
