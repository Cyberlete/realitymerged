package org.reality.security

import cats.syntax.either._

import org.reality.ext.derevo.ordering
import org.reality.kryo.JsonSerializer
import org.reality.security.hash.Hash

import com.google.common.hash.Hashing
import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Encoder
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.scalacheck.{Arbitrary, Gen}

object hash {

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class Hash(value: String)

  object Hash {

    def fromBytes(bytes: Array[Byte]): Hash =
      Hash(Hashing.sha256().hashBytes(bytes).toString)

    def empty: Hash = Hash(s"%064d".format(0))

    implicit val arbitrary: Arbitrary[Hash] = Arbitrary(Gen.stringOfN(64, Gen.hexChar.map(_.toLower)).map(Hash(_)))
  }

  @derive(encoder, decoder, ordering, order, show)
  @newtype
  case class ProofsHash(value: String)

  object ProofsHash {
    implicit val arbitrary: Arbitrary[ProofsHash] = Arbitrary(
      Arbitrary.arbitrary[Hash].map(h => ProofsHash(h.coerce[String]))
    )
  }

}

trait Hashable[F[_]] {
  def hash[A: Encoder](data: A): Either[Throwable, Hash]
}

object Hashable {

  def forJson[F[_]]: Hashable[F] = new Hashable[F] {

    def hash[A: Encoder](data: A): Either[Throwable, Hash] = {
      data match {
        case d: Encodable[_] =>
          JsonSerializer.serialize(d.toEncode)(d.jsonEncoder)
        case _ =>
          JsonSerializer.serialize(data)
      }
    }
      .asRight[Throwable] // unnecessary
      .map(Hash.fromBytes)
  }
}
