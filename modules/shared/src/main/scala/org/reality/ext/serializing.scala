package org.reality.ext

import org.reality.kryo.JsonSerializer

import _root_.cats.MonadThrow
import _root_.cats.syntax.applicative._
import _root_.cats.syntax.either._
import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}

object serializing {

  implicit class RefinedSerializer[F[_], A: Encoder](a: A) {

    def toBinary: Either[Throwable, Array[Byte]] =
      JsonSerializer.serialize(a).asRight[Throwable] // unneccessary
  }

  implicit class RefinedSerializerF[F[_]: MonadThrow, A: Encoder](a: A) {

    def toBinaryF: F[Array[Byte]] =
      JsonSerializer.serialize(a).pure[F]
  }

  implicit class RefinedDeserializer[F[_]](bytes: Array[Byte]) {

    def fromBinary[A: Decoder]: Either[Throwable, A] =
      JsonSerializer.deserialize[A](bytes)
  }

  implicit class RefinedDeserializerF[F[_]: MonadThrow](bytes: Array[Byte]) {

    def fromBinaryF[A: Decoder]: F[A] =
      JsonSerializer.deserialize[A](bytes).liftTo[F]
  }
}
