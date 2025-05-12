package org.reality.dag.l1.domain.consensus.block

import org.reality.dag.domain.block.NETBlock
import org.reality.dag.l1.domain.consensus.round.RoundId
import org.reality.kernel.Ω
import org.reality.security.Hashed

sealed trait BlockConsensusOutput

object BlockConsensusOutput {
  case class FinalBlock(hashedBlock: Hashed[NETBlock]) extends Ω with BlockConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends Ω with BlockConsensusOutput
}
