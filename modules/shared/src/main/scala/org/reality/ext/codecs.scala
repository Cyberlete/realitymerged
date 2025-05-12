package org.reality.ext

import java.util.concurrent.TimeUnit

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import org.reality.kryo.JsonSerializer

import _root_.cats.Order
import _root_.cats.data.NonEmptySet
import _root_.cats.effect.Concurrent
import _root_.cats.syntax.all._
import io.circe._
import io.circe.syntax._
import org.http4s.DecodeResult.{failureT, successT}
import org.http4s.{EntityDecoder, EntityEncoder, MalformedMessageBodyFailure}

object codecs {

  object BinaryCodec {
    implicit def encoder[F[_], A: Encoder]: EntityEncoder[F, A] =
      EntityEncoder.byteArrayEncoder[F].contramap { a =>
        JsonSerializer.serialize(a)
      }

    implicit def decoder[F[_]: Concurrent, A: Decoder]: EntityDecoder[F, A] =
      EntityDecoder.byteArrayDecoder[F].flatMapR { bytes =>
        JsonSerializer.deserialize[A](bytes) match {
          case Right(value) => successT[F, A](value)
          case Left(ex) =>
            failureT(MalformedMessageBodyFailure("Failed to deserialize http entity body with Kryo", ex.some))
        }
      }
  }

  object NonEmptySetCodec {

    def decoder[A: Decoder: Ordering]: Decoder[NonEmptySet[A]] =
      Decoder.decodeNonEmptySet[A](Decoder[A], Order.fromOrdering).map { nes =>
        NonEmptySet.fromSetUnsafe(SortedSet.from(nes.toSortedSet))
      }
  }

  object FiniteDurationCodec {
    implicit val encoder: Encoder[FiniteDuration] = Encoder.instance { duration =>
      Json.obj(
        "length" -> duration.length.asJson,
        "unit" -> duration.unit.name.asJson
      )
    }

    implicit val decoder: Decoder[FiniteDuration] = Decoder.instance { cursor =>
      for {
        length <- cursor.get[Long]("length")
        unit <- cursor.get[String]("unit").flatMap { unitStr =>
          Try(TimeUnit.valueOf(unitStr)).toEither
            .leftMap(_ => DecodingFailure("Invalid TimeUnit", cursor.history))
        }
      } yield FiniteDuration(length, unit)
    }
  }
}
