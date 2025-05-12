package org.reality.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.domain.block.NETBlock
import org.reality.schema.gossip.RumorRaw
import org.reality.sdk.app.SDK
import org.reality.security.Hashed
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

object L0Queues {

  def make[F[_]: Concurrent](sdk: SDK[F]): F[L0Queues[F]] =
    for {
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
      l1OutputQueue <- Queue.unbounded[F, Signed[NETBlock]]
    } yield
      new L0Queues[F] {
        val rumor = sdk.sdkQueues.rumor
        val stateChannelOutput = stateChannelOutputQueue
        val l1Output = l1OutputQueue
      }
}

abstract class L0Queues[F[_]] extends Queues[F] {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val stateChannelOutput: Queue[F, StateChannelOutput]
  val l1Output: Queue[F, Signed[NETBlock]]
}

abstract class Queues[F[_]]
