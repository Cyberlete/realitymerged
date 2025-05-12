package org.reality.dag.snapshot

import cats.MonadThrow
import cats.data.Validated
import cats.effect.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.util.control.NoStackTrace

import org.reality.schema.SnapshotOrdinal
import org.reality.security.signature.Signed
import org.reality.security.{Hashed, hash}

import derevo.cats.{eqv, show}
import derevo.derive

object StateProofValidator {

  def validate[F[_]: Async](
    snapshot: Signed[GlobalSnapshot],
    si: GlobalSnapshotInfo
  ): F[Validated[StateBroken, Unit]] = snapshot.toHashed.flatMap(hs => si.stateProof.map(validate(hs, _)))

  def validate[F[_]: MonadThrow](
    snapshot: Hashed[GlobalSnapshot],
    si: GlobalSnapshotInfo
  ): F[Validated[StateBroken, Unit]] = si.stateProof.map(validate(snapshot, _))

  def validate(
    snapshot: Hashed[GlobalSnapshot],
    stateProof: GlobalSnapshotStateProof
  ): Validated[StateBroken, Unit] =
    Validated.cond(stateProof === snapshot.signed.value.stateProof, (), StateBroken(snapshot.ordinal, snapshot.hash))

  @derive(eqv, show)
  case class StateBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: hash.Hash) extends NoStackTrace
}
