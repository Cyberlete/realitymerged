package org.reality.dag.block.processing

import org.reality.dag.domain.block.NETBlock
import org.reality.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceResult(
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[NETBlock], NonNegLong)],
  notAccepted: List[(Signed[NETBlock], BlockNotAcceptedReason)]
)
