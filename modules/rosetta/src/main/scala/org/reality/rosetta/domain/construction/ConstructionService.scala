package org.reality.rosetta.domain.construction

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.functor._

import org.reality.rosetta.domain.error.{ConstructionError, InvalidPublicKey}
import org.reality.rosetta.domain.{AccountIdentifier, RosettaPublicKey}
import org.reality.security.SecurityProvider
import org.reality.security.key.ops._

trait ConstructionService[F[_]] {
  def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier]
}

object ConstructionService {
  def make[F[_]: Async: SecurityProvider](): ConstructionService[F] = new ConstructionService[F] {
    def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier] =
      publicKey.hexBytes
        .toPublicKeyByEC[F]
        .map(_.toAddress)
        .map(AccountIdentifier(_, None))
        .attemptT
        .leftMap(_ => InvalidPublicKey)
  }
}
