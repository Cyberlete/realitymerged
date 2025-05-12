package org.reality.sdk.domain.snapshot.storage

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.height.Height
import org.reality.security.Hashed

trait LastGlobalSnapshotStorage[F[_]] {
  def set(snapshot: Hashed[GlobalSnapshot], state: GlobalSnapshotInfo): F[Unit]
  def setInitial(snapshot: Hashed[GlobalSnapshot], state: GlobalSnapshotInfo): F[Unit]
  def get: F[Option[Hashed[GlobalSnapshot]]]
  def getCombined: F[Option[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
