package org.reality.sdk.infrastructure

import cats.data.{Kleisli, OptionT}

import org.reality.schema.gossip.RumorRaw
import org.reality.schema.peer.PeerId

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (RumorRaw, PeerId), Unit]

  val rumorLoggerName = "RumorLogger"

}
