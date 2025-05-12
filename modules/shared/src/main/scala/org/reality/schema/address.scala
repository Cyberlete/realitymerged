package org.reality.schema

import org.reality.ext.cats.data.OrderBasedOrdering
import org.reality.ext.refined._
import org.reality.schema.balance.Balance
import org.reality.security.Base58

import derevo.cats.{order, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe._
import io.estatico.newtype.macros.newtype

object address {

  @derive(decoder, encoder, keyDecoder, keyEncoder, order, show)
  @newtype
  case class Address(value: NETAddress)

  object Address {
    implicit object OrderingInstance extends OrderBasedOrdering[Address]

    implicit val decodeNETAddress: Decoder[NETAddress] =
      decoderOf[String, NETAddressRefined]

    implicit val encodeNETAddress: Encoder[NETAddress] =
      encoderOf[String, NETAddressRefined]

    implicit val keyDecodeNETAddress: KeyDecoder[NETAddress] = new KeyDecoder[NETAddress] {
      def apply(key: String): Option[NETAddress] = refineV[NETAddressRefined](key).toOption
    }

    implicit val keyEncodeNETAddress: KeyEncoder[NETAddress] = new KeyEncoder[NETAddress] {
      def apply(key: NETAddress): String = key.value
    }
  }

  case class AddressCache(balance: Balance)

  final case class NETAddressRefined()

  object NETAddressRefined {
    implicit def addressCorrectValidate: Validate.Plain[String, NETAddressRefined] =
      Validate.fromPredicate(
        {
          case a if a == StardustCollective.address => true
          case a if a.length != 40                  => false
          case a =>
            val par = a.substring(4).filter(Character.isDigit).map(_.toString.toInt).sum % 9

            val isBase58 = Base58.isBase58(a.substring(4))
            val hasNETPrefixAndParity = a.startsWith(s"NET$par")

            isBase58 && hasNETPrefixAndParity
        },
        a => s"Invalid NET address: $a",
        NETAddressRefined()
      )
  }

  type NETAddress = String Refined NETAddressRefined
}
