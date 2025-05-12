package org.reality.combined

import cats.effect.IO

import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.{Address, NETAddressRefined}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.refineV

object RefinementUtils {
  def parseAddress(addressStr: String): IO[Address] =
    IO.fromOption(
      refineV[NETAddressRefined](addressStr).toOption.map(Address(_))
    )(new IllegalArgumentException(s"Invalid NET address: $addressStr"))

  def parsePositiveAmount(amount: Long): IO[Refined[Long, Positive]] =
    IO.fromEither(
      refineV[Positive](amount).left.map(err => new IllegalArgumentException(s"Invalid amount: $amount. Error: $err"))
    )

  def parseNonNegativeAmount(amount: Long): IO[Refined[Long, NonNegative]] =
    IO.fromEither(
      refineV[NonNegative](amount).left.map(err => new IllegalArgumentException(s"Invalid fee: $amount. Error: $err"))
    )

  def parseOrdinal(value: Long): IO[SnapshotOrdinal] =
    IO.fromEither(
      refineV[NonNegative](value).left.map(err => new IllegalArgumentException(s"Invalid ordinal: $value. Error: $err"))
    ).map(SnapshotOrdinal(_))
}
