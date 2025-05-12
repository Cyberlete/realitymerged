package org.reality

import java.security.MessageDigest

import cats.{Functor, ~>}

import fs2.Stream

package object utils {
  def binaryHash(appData: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(appData)
    val digest = md.digest()
    digest.map("%02x".format(_)).mkString
  }

  def streamLiftK[F[_]](implicit F: Functor[F]): F ~> Stream[F, *] = new (F ~> Stream[F, *]) {
    override def apply[A](fa: F[A]): Stream[F, A] = Stream.eval(fa)
  }
}
