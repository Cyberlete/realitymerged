package org.reality.combined.node

import cats.effect.IO
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import eu.timepit.refined.types.numeric.NonNegLong
import eu.timepit.refined.api.Refined
import scala.util.Try

object Parsing {
  def parseOrdinal(ordinal: Long): IO[SnapshotOrdinal] =
    if (ordinal < 0) {
      IO.raiseError(new IllegalArgumentException(s"Ordinal must be non-negative, got $ordinal"))
    } else {
      // Create a NonNegLong required by SnapshotOrdinal
      val refinedOrdinal = NonNegLong.from(ordinal) match {
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(s"Invalid ordinal: $error")
      }
      IO.pure(SnapshotOrdinal(refinedOrdinal))
    }

  def parseAddress(addressStr: String): IO[Address] =
    // Use the Address.fromString method if available
    Try(Address.fromString(addressStr)).fold(
      error => IO.raiseError(new IllegalArgumentException(s"Invalid address format: $addressStr", error)),
      address => IO.pure(address)
    )

  def parsePositiveAmount(amount: Long): IO[Long] =
    if (amount <= 0) {
      IO.raiseError(new IllegalArgumentException(s"Amount must be positive, got $amount"))
    } else {
      IO.pure(amount)
    }

  def parseNonNegativeAmount(amount: Long): IO[Long] =
    if (amount < 0) {
      IO.raiseError(new IllegalArgumentException(s"Amount must be non-negative, got $amount"))
    } else {
      IO.pure(amount)
    }
}
