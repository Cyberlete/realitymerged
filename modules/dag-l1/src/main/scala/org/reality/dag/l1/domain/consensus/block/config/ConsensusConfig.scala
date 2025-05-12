package org.reality.dag.l1.domain.consensus.block.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}

case class ConsensusConfig(
  peersCount: PosInt = PosInt(1),
  tipsCount: PosInt = PosInt(1),
  timeout: FiniteDuration = 1.second,
  pullTxsCount: NonNegLong = NonNegLong(1L)
)
