package org.reality.security

import java.math.BigInteger
import java.nio.charset.Charset
import java.security.interfaces.ECPrivateKey
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair, PublicKey}

import cats.Show
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.ext.derevo.ordering
import org.reality.security.key._
import org.reality.security.key.ops.PublicKeyOps

import derevo.cats.{eqv, order}
import derevo.circe.magnolia._
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.{ECPrivateKeySpec, ECPublicKeySpec}
import org.scalacheck.{Arbitrary, Gen}

object hex {

  @derive(decoder, encoder, eqv, order, ordering, keyEncoder, keyDecoder)
  @newtype
  case class Hex(value: String) {

    def toBytes: Array[Byte] =
      if (value.contains(" ")) {
        value.split(" ").map(Integer.parseInt(_, 16).toByte)
      } else if (value.contains("-")) {
        value.split("-").map(Integer.parseInt(_, 16).toByte)
      } else {
        value.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
      }

    def toBytes(charset: Charset): Array[Byte] =
      value.getBytes(charset)

    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] =
      for {
        _ <- Async[F].unit
        prefixed = (PublicKeyHexPrefix + value).coerce[Hex]
        encodedBytes = prefixed.toBytes
        spec <- Async[F].delay {
          new X509EncodedKeySpec(encodedBytes)
        }
        kf <- Async[F].delay {
          KeyFactory.getInstance(ECDSA, SecurityProvider[F].provider)
        }
        pk <- Async[F].delay {
          kf.generatePublic(spec)
        }
      } yield pk

    def toKeyPairFromPrivate[F[_]: Async: SecurityProvider]: F[KeyPair] =
      for {
        privateKeyBigInt <- Async[F].delay(new BigInteger(value, 16))
        ecParameterSpec <- Async[F].delay(ECNamedCurveTable.getParameterSpec(secp256k))
        privateKeySpec <- Async[F].delay(new ECPrivateKeySpec(privateKeyBigInt, ecParameterSpec))
        keyFactory <- Async[F].delay(KeyFactory.getInstance(ECDSA, SecurityProvider[F].provider))
        privateKey <- Async[F].delay(keyFactory.generatePrivate(privateKeySpec))

        q <- Async[F].delay(ecParameterSpec.getG.multiply(privateKey.asInstanceOf[ECPrivateKey].getS))
        publicSpec <- Async[F].delay(new ECPublicKeySpec(q, ecParameterSpec))
        publicKey <- Async[F].delay(keyFactory.generatePublic(publicSpec))

        privateKeyInPKCSFormatHex = (PrivateKeyHexPrefix + value + secp256kHexIdentifier + "04" + publicKey.toHex.coerce).coerce[Hex]
        privateKeyInPKCSFormatSpec = new PKCS8EncodedKeySpec(privateKeyInPKCSFormatHex.toBytes)
        privateKeyPKCS = keyFactory.generatePrivate(privateKeyInPKCSFormatSpec)
        kp = new KeyPair(publicKey, privateKeyPKCS)
      } yield kp

    def toPublicKeyByEC[F[_]: Async: SecurityProvider]: F[PublicKey] =
      for {
        curve <- Async[F].delay {
          ECNamedCurveTable.getParameterSpec(secp256k)
        }

        encodedBytes = value.coerce[Hex].toBytes

        spec <- Async[F].delay {
          new ECPublicKeySpec(
            curve.getCurve().decodePoint(encodedBytes),
            curve
          )
        }

        kf <- Async[F].delay {
          KeyFactory.getInstance(ECDSA, SecurityProvider[F].provider)
        }
        pk <- Async[F].delay {
          kf.generatePublic(spec)
        }

      } yield pk

    def shortValue: String = value.take(8)
  }

  object Hex {

    implicit val show: Show[Hex] = Show.show(_.shortValue)

    implicit val arbitrary: Arbitrary[Hex] =
      Arbitrary(Gen.sized(size => Gen.stringOfN((size / 2) * 2, Gen.hexChar).map(_.toLowerCase).map(Hex(_))))

    def fromBytes(bytes: Array[Byte], sep: Option[String] = None): Hex =
      sep match {
        case None => bytes.map("%02x".format(_)).mkString.coerce
        case _    => bytes.map("%02x".format(_)).mkString(sep.get).coerce
      }
  }

}
