package org.reality.ext.http4s

import cats.syntax.option._

import org.reality.security.hash.Hash

object HashVar {
  def unapply(str: String): Option[Hash] = Hash(str).some
}
