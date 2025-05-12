package org.reality.ext

import java.security.KeyPair

import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.security.{Hashable, SecurityProvider}

import _root_.cats.MonadThrow
import _root_.cats.data.NonEmptyList
import _root_.cats.effect.kernel.Async
import _root_.cats.syntax.either._
import _root_.cats.syntax.flatMap._
import io.circe.Encoder

object crypto {
  implicit class RefinedHashable[F[_], A: Encoder](a: A) {

    def hash: Either[Throwable, Hash] = Hashable.forJson[F].hash(a)
  }

  implicit class RefinedHashableF[F[_]: MonadThrow, A: Encoder](a: A) {

    def hashF: F[Hash] = Hashable.forJson[F].hash(a).liftTo[F]
  }

  implicit class RefinedSignedF[F[_]: Async: SecurityProvider, A: Encoder](data: A) {

    def sign(keyPair: KeyPair): F[Signed[A]] = Signed.forAsyncJson[F, A](data, keyPair)

    def sign(keyPairs: NonEmptyList[KeyPair]): F[Signed[A]] =
      keyPairs.tail.foldLeft(sign(keyPairs.head)) { (acc, keyPair) =>
        acc.flatMap(_.signAlsoWith(keyPair))
      }
  }
}
