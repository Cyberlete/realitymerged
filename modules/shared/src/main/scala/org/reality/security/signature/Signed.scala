package org.reality.security.signature

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.contravariant._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.order._
import cats.syntax.show._
import cats.{Order, Show}

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.reality.ext.codecs.NonEmptySetCodec
import org.reality.ext.crypto._
import org.reality.schema.ID.Id
import org.reality.security.hash.ProofsHash
import org.reality.security.signature.signature.SignatureProof
import org.reality.security.{Hashed, SecurityProvider}

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.ops._
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class Signed[+A](value: A, proofs: NonEmptySet[SignatureProof])

object Signed {
  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  case class InvalidSignatureForHash[A](signed: Signed[A]) extends NoStackTrace

  implicit def show[A: Show]: Show[Signed[A]] =
    s => s"Signed(value=${s.value.show}, proofs=${s.proofs.show})"

  implicit def encoder[A: Encoder]: Encoder[Signed[A]] = deriveEncoder

  implicit val proofsDecoder: Decoder[NonEmptySet[SignatureProof]] = NonEmptySetCodec.decoder[SignatureProof]

  implicit def decoder[A: Decoder]: Decoder[Signed[A]] = deriveDecoder

  implicit def autoUnwrap[T](t: Signed[T]): T = t.value

  implicit def order[A: Order]: Order[Signed[A]] = Order.fromOrdering(ordering(Order[A].toOrdering))

  implicit def ordering[A: Ordering]: Ordering[Signed[A]] = new SignedOrdering[A]()

  implicit def _arbitrary[A: Arbitrary]: Arbitrary[Signed[A]] =
    Arbitrary(for {
      value <- arbitrary[A]
      head <- arbitrary[SignatureProof]
      tail <- arbitrary[SortedSet[SignatureProof]]
    } yield Signed(value, NonEmptySet(head, tail)))

  def forAsyncJson[F[_]: Async: SecurityProvider, A: Encoder](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    SignatureProof.fromData(keyPair)(data).map { sp =>
      Signed[A](data, NonEmptySet.fromSetUnsafe(SortedSet(sp)))
    }

  implicit class SignedOps[A: Encoder](signed: Signed[A]) {

    def addProof(proof: SignatureProof): Signed[A] =
      signed.copy(proofs = NonEmptySet.fromSetUnsafe(signed.proofs.toSortedSet + proof))

    def signAlsoWith[F[_]: Async: SecurityProvider](keyPair: KeyPair): F[Signed[A]] =
      SignatureProof.fromData(keyPair)(signed.value).map { sp =>
        Signed(signed.value, signed.proofs.add(sp))
      }

    def isSignedBy(signer: Id): Boolean = isSignedBy(Set(signer))

    def isSignedBy(signers: Set[Id]): Boolean =
      signers.forall(signed.proofs.map(_.id).contains(_))

    def isSignedExclusivelyBy(signer: Id): Boolean = isSignedExclusivelyBy(Set(signer))

    def isSignedExclusivelyBy(signers: Set[Id]): Boolean =
      signed.proofs.map(_.id).toSortedSet.unsorted === signers

    def hasValidSignature[F[_]: Async: SecurityProvider]: F[Boolean] =
      validProofs.flatMap {
        case Left(invalidProofs) =>
          logger.error(s"Invalid proofs: ${invalidProofs}").as(false) // Logging invalid proofs
        case Right(_) => true.pure[F]
      }

    def validProofs[F[_]: Async: SecurityProvider]: F[Either[NonEmptySet[SignatureProof], NonEmptySet[SignatureProof]]] =
      for {
        hash <- signed.value.hashF
        invalidOrValidProofs <- signed.proofs.toNonEmptyList.traverse { proof =>
          for {
            result <- signature.verifySignatureProof(hash, proof)
          } yield proof -> result
        }.map { proofsAndResults =>
          proofsAndResults
            .filterNot(_._2)
            .map(_._1)
            .toNel
            .map(_.toNes)
            .toLeft(signed.proofs)
        }
      } yield invalidOrValidProofs

    def toHashedWithSignatureCheck[F[_]: Async: SecurityProvider]: F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      hasValidSignature.ifM(
        toHashed.map(_.asRight[InvalidSignatureForHash[A]]),
        InvalidSignatureForHash(signed).asLeft[Hashed[A]].pure[F]
      )

    def toHashed[F[_]: Async]: F[Hashed[A]] =
      signed.value.hashF.flatMap { hash =>
        proofsHash.map { proofsHash =>
          Hashed(signed, hash, proofsHash)
        }
      }

    def proofsHash[F[_]: Async]: F[ProofsHash] =
      signed.proofs.toSortedSet.hashF
        .map(hash => ProofsHash(hash.coerce))
  }

  final class SignedOrdering[A](implicit evidence$25: Ordering[A]) extends Ordering[Signed[A]] {

    def compare(x: Signed[A], y: Signed[A]): Int =
      Order
        .whenEqual(
          Order.fromOrdering(Ordering[A]).contramap[Signed[A]](s => s.value),
          Order[NonEmptySet[SignatureProof]].contramap[Signed[A]](s => s.proofs)
        )
        .compare(x, y)
  }
}
