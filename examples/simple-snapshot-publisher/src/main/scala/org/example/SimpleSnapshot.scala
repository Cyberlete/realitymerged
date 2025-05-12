package org.example

import org.reality.kernel.StateChannelSnapshot
import org.reality.security.hash.Hash

case class SimpleSnapshot(lastSnapshotHash: Hash) extends StateChannelSnapshot
