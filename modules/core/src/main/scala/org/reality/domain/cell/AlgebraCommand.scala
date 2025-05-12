package org.reality.domain.cell

import org.reality.dag.domain.block.NETBlock
import org.reality.kernel.Ω
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueNETL1Data(data: Signed[NETBlock]) extends AlgebraCommand
  case class EnqueueETHL1Data(data: ETHL1Block) extends AlgebraCommand
//  case class EnqueueETHBlock(data: OmegaEthBlockWrapper) extends AlgebraCommand

  case object NoAction extends AlgebraCommand
}

case class EthL1Transaction(
  a: Int,
  src: String,
  dst: String,
  parentHash: String = "",
  ordinal: Int = 0
) extends Ω {
  val hash = s"$a$src$dst$ordinal$parentHash"

  override def toString: String =
    s"L1Transaction($hash)"
}
case class L1Edge(txs: Set[EthL1Transaction]) extends Ω

object EthL1Transaction {}

case class ETHL1Block(txs: Set[EthL1Transaction]) extends Ω {

  def height: Int =
    txs.maxByOption(_.a).map(_.a).getOrElse(0) // TODO: height should be based on block parents (parents + 1)
}
