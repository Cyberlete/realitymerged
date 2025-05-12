package org.example

import org.reality.kernel.Ω
import org.reality.kernel.StateChannelSnapshot
import org.reality.security.hash.Hash

object types {

  case class L0TokenTransaction()

  sealed trait L0TokenStep extends Ω
  case class L0TokenBlock(transactions: Set[L0TokenTransaction]) extends L0TokenStep
  case class CreateStateChannelSnapshot() extends L0TokenStep

  case class L0TokenStateChannelSnapshot() extends StateChannelSnapshot {
    val lastSnapshotHash: Hash = ???
  }
}
