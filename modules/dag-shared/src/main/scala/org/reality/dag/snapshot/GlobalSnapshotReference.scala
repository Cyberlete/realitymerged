package org.reality.dag.snapshot

import org.reality.schema.SnapshotOrdinal
import org.reality.schema.height.{Height, SubHeight}
import org.reality.security.Hashed
import org.reality.security.hash.{Hash, ProofsHash}

import derevo.cats.show
import derevo.derive

@derive(show)
case class GlobalSnapshotReference(
  height: Height,
  subHeight: SubHeight,
  ordinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  hash: Hash,
  proofsHash: ProofsHash
)

object GlobalSnapshotReference {

  def fromHashedGlobalSnapshot(snapshot: Hashed[GlobalSnapshot]): GlobalSnapshotReference =
    GlobalSnapshotReference(
      snapshot.height,
      snapshot.subHeight,
      snapshot.ordinal,
      snapshot.lastSnapshotHash,
      snapshot.hash,
      snapshot.proofsHash
    )
}
