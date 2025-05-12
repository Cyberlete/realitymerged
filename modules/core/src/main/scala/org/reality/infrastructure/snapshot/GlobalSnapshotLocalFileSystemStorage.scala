package org.reality.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.util.Random

import org.reality.dag.snapshot.GlobalSnapshot
import org.reality.ext.crypto._
import org.reality.schema.SnapshotOrdinal
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.storage.LocalFileSystemStorage

import eu.timepit.refined.auto._
import fs2.io.file.Path
import io.estatico.newtype.ops._

final class GlobalSnapshotLocalFileSystemStorage[F[_]: Async] private (path: Path)
    extends LocalFileSystemStorage[F, Signed[GlobalSnapshot]](path) {

  def write(snapshot: Signed[GlobalSnapshot]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    toHashName(snapshot.value).flatMap { hashName: String =>
      (exists(ordinalName), exists(hashName)).mapN {
        case (ordinalExists, hashExists) =>
          if (ordinalExists || hashExists) {
            (new Throwable("Snapshot already exists under ordinal or hash filename")).raiseError[F, Unit]
          } else {
            val random = Random
            random.setSeed(1337L)
//            ordinalExists.pure[F]()
            write(hashName + random.nextPrintableChar(), snapshot) >> link(
              hashName + random.nextPrintableChar(),
              ordinalName
            ) // todo need uuid for local disk
          }
      }.flatten
    }

  }

  def read(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] =
    read(toOrdinalName(ordinal))

  def read(hash: Hash): F[Option[Signed[GlobalSnapshot]]] =
    read(hash.coerce[String])

  private def toOrdinalName(snapshot: GlobalSnapshot): String = toOrdinalName(snapshot.ordinal)
  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  private def toHashName(snapshot: GlobalSnapshot): F[String] = snapshot.hashF.map(_.coerce[String])

}

object GlobalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async](path: Path): F[GlobalSnapshotLocalFileSystemStorage[F]] =
    Applicative[F].pure(new GlobalSnapshotLocalFileSystemStorage[F](path)).flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
