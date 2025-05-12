package org.reality.infrastructure

import org.reality.dag.domain.block.NETBlock
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.sdk.infrastructure.consensus.Consensus
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

package object snapshot {

  type NETEvent = Signed[NETBlock]

  type StateChannelEvent = StateChannelOutput

  type GlobalSnapshotEvent = Either[StateChannelEvent, NETEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalSnapshot

  type GlobalSnapshotContext = GlobalSnapshotInfo

  type GlobalSnapshotConsensus[F[_]] = Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]

}
