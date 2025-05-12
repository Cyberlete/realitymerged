package org.reality.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import org.reality.dag.snapshot.GlobalSnapshotInfo
import org.reality.schema.SnapshotOrdinal
import org.reality.storage.LocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path

final class GlobalSnapshotInfoLocalFileSystemStorage[F[_]: Async] private (path: Path)
    extends LocalFileSystemStorage[F, GlobalSnapshotInfo](path) {
  def write(ordinal: SnapshotOrdinal, snapshotInfo: GlobalSnapshotInfo): F[Unit] =
    write(toOrdinalName(ordinal), snapshotInfo)

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal.fromLong))
        .flattenOption
    }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString
}

object GlobalSnapshotInfoLocalFileSystemStorage {
  def make[F[_]: Async](path: Path): F[GlobalSnapshotInfoLocalFileSystemStorage[F]] =
    Applicative[F].pure(new GlobalSnapshotInfoLocalFileSystemStorage[F](path)).flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
