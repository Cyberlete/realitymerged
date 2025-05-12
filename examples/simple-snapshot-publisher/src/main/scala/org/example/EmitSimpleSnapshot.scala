package org.example

import org.reality.kernel.Ω
import org.reality.security.hash.Hash

case class EmitSimpleSnapshot(lastSnapshotHash: Hash) extends Ω
