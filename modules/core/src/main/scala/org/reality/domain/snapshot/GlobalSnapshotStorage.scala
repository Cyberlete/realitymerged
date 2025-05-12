package org.reality.domain.snapshot

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.transaction.{DeployAppTransactionInfo, RegisterAppProviderTransactionInfo}
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

trait GlobalSnapshotStorage[F[_]] {
  def prepend(snapshot: Signed[GlobalSnapshot], state: GlobalSnapshotInfo): F[Boolean]

  def head: F[Option[(Signed[GlobalSnapshot], GlobalSnapshotInfo)]]
  def headSnapshot: F[Option[Signed[GlobalSnapshot]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]

  def get(hash: Hash): F[Option[Signed[GlobalSnapshot]]]

  def getDeployAppTransaction(appIdentifier: String): F[Option[DeployAppTransactionInfo]]

  def getAppProvider(appIdentifier: String): F[Option[RegisterAppProviderTransactionInfo]]
  def getRange(start: SnapshotOrdinal, end: SnapshotOrdinal): F[List[Signed[GlobalSnapshot]]]

}
