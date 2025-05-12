package org.reality.schema

import java.security.PublicKey

import cats.effect.Async
import cats.syntax.functor._

import org.reality.ext.derevo.ordering
import org.reality.schema.address.Address
import org.reality.schema.peer.PeerId
import org.reality.security.SecurityProvider
import org.reality.security.hex.Hex
import org.reality.security.key.ops._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import io.estatico.newtype.macros.newtype

object ID {

  @derive(arbitrary, decoder, encoder, eqv, show, order, ordering)
  @newtype
  case class Id(hex: Hex) {
    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] = hex.toPublicKey

    def toAddress[F[_]: Async: SecurityProvider]: F[Address] = toPublicKey.map(_.toAddress)
  }

  implicit class IdOps(id: Id) {
    def toPeerId: PeerId = PeerId._Id.reverseGet(id)
  }
}
