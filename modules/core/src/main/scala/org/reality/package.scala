package org

import java.security.PublicKey

import cats.effect.Async

import scala.util.control.NoStackTrace

import org.reality.schema.peer.PeerId
import org.reality.security.SecurityProvider

import eu.timepit.refined.auto._
import io.estatico.newtype.ops._

package object reality {

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

  case object OwnCollateralNotSatisfied extends NoStackTrace

}
