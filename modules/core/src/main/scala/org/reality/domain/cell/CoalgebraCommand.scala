package org.reality.domain.cell

import org.reality.dag.domain.block.NETBlock
import org.reality.kernel.Ω
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

sealed trait CoalgebraCommand extends Ω

object CoalgebraCommand {
  case class ProcessNETL1(data: Signed[NETBlock]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
  case class Empty() extends CoalgebraCommand
}
