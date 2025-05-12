package org.reality.schema

import org.reality.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAsActiveTip[B <: Block[_]] {
  val block: Signed[B]
  val usageCount: NonNegLong
}
