package org.reality.schema

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.util.Try

import org.reality.ext.cats.data.OrderBasedOrdering
import org.reality.ext.crypto._
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.security.{Encodable, Hashed}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._
import eu.timepit.refined.auto.{autoInfer, autoRefineV, autoUnwrap}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.Lens
import monocle.macros.GenLens

object transaction {

  @derive(decoder, encoder, order, show)
  sealed trait Transaction {
    def source: Address

    def destination: Address

    def fee: TransactionFee

    def parent: TransactionReference

    def amount: TransactionAmount

    def salt: TransactionSalt

    val ordinal: TransactionOrdinal
  }

  object Transaction {
    implicit object OrderingInstance extends OrderBasedOrdering[Transaction]
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionAmount(value: PosLong)

  object TransactionAmount {
    implicit def toAmount(amount: TransactionAmount): Amount = Amount(amount.value)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionFee(value: NonNegLong)

  object TransactionFee {
    implicit def toAmount(fee: TransactionFee): Amount = Amount(fee.value)
    val zero: TransactionFee = TransactionFee(0L)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionOrdinal(value: NonNegLong) {
    def next: TransactionOrdinal = TransactionOrdinal(value |+| 1L)
  }

  object TransactionOrdinal {
    val first: TransactionOrdinal = TransactionOrdinal(1L)
  }

  @derive(decoder, encoder, order, show)
  case class TransactionReference(ordinal: TransactionOrdinal, hash: Hash)

  object TransactionReference {
    val empty: TransactionReference = TransactionReference(TransactionOrdinal(0L), Hash.empty)

    val _Hash: Lens[TransactionReference, Hash] = GenLens[TransactionReference](_.hash)
    val _Ordinal: Lens[TransactionReference, TransactionOrdinal] = GenLens[TransactionReference](_.ordinal)

    def of[F[_]: Async](signedTransaction: Signed[Transaction]): F[TransactionReference] =
      signedTransaction.value.hashF.map(TransactionReference(signedTransaction.ordinal, _))

    def of(hashedTransaction: Hashed[Transaction]): TransactionReference =
      TransactionReference(hashedTransaction.ordinal, hashedTransaction.hash)

  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionSalt(value: Long)

  trait BaseTransactionData {
    def source: Address
    def destination: Address
    def fee: TransactionFee
  }

  @derive(decoder, encoder, order, show)
  case class StandardTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction
      with Encodable[String] {
    import StandardTransaction._

    // WARN: Transactions hash needs to be calculated with Kryo instance having setReferences=true, to be backward compatible
    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            amount.coerce.value.toHexString,
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    override def jsonEncoder: Encoder[String] = implicitly

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next
  }

  object StandardTransaction {

    implicit object OrderingInstance extends OrderBasedOrdering[StandardTransaction]

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[StandardTransaction, Address] = GenLens[StandardTransaction](_.source)
    val _Destination: Lens[StandardTransaction, Address] = GenLens[StandardTransaction](_.destination)

    val _Amount: Lens[StandardTransaction, TransactionAmount] = GenLens[StandardTransaction](_.amount)
    val _Fee: Lens[StandardTransaction, TransactionFee] = GenLens[StandardTransaction](_.fee)
    val _Parent: Lens[StandardTransaction, TransactionReference] = GenLens[StandardTransaction](_.parent)

    val _ParentHash: Lens[StandardTransaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[StandardTransaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

  @derive(decoder, encoder, order, show)
  case class SwapTx(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    amtDest: TransactionAmount,
    stake: TransactionAmount, // todo lose your stake if you don't complete tx, use for prioritizing tx acceptance
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction
      with Encodable[String] {
    import SwapTx._

    // WARN: Transactions hash needs to be calculated with Kryo instance having setReferences=true, to be backward compatible
    override def toEncode: String =
      "2" +
        SwapTx.runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            amount.coerce.value.toHexString,
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    override def jsonEncoder: Encoder[String] = implicitly

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next
  }

  object SwapTx {

    implicit object OrderingInstance extends OrderBasedOrdering[SwapTx]

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[SwapTx, Address] = GenLens[SwapTx](_.source)
    val _Destination: Lens[SwapTx, Address] = GenLens[SwapTx](_.destination)

    val _Amount: Lens[SwapTx, TransactionAmount] = GenLens[SwapTx](_.amount)
    val _Fee: Lens[SwapTx, TransactionFee] = GenLens[SwapTx](_.fee)
    val _Parent: Lens[SwapTx, TransactionReference] = GenLens[SwapTx](_.parent)

    val _ParentHash: Lens[SwapTx, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[SwapTx, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

  @derive(decoder, encoder, order, show)
  case class RewardTransaction(
    destination: Address,
    amount: TransactionAmount
  )

  object RewardTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[RewardTransaction]
  }

  @derive(decoder, encoder, show)
  case class TransactionView(
    transaction: Transaction,
    hash: Hash,
    status: TransactionStatus
  )

  @derive(eqv, show)
  sealed trait TransactionStatus extends EnumEntry

  object TransactionStatus extends Enum[TransactionStatus] with TransactionStatusCodecs {
    val values = findValues

    case object Waiting extends TransactionStatus
  }

  trait TransactionStatusCodecs {
    implicit val encode: Encoder[TransactionStatus] = Encoder.encodeString.contramap[TransactionStatus](_.entryName)
    implicit val decode: Decoder[TransactionStatus] =
      Decoder.decodeString.emapTry(s => Try(TransactionStatus.withName(s)))
  }

  @derive(decoder, encoder, order, show)
  case class RegisterAppProviderTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    appIdentifier: String,
    host: String,
    port: String,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction
      with Encodable[String] {
    import RegisterAppProviderTransaction._

    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            amount.coerce.value.toHexString,
            appIdentifier,
            host,
            port,
            fee.coerce.value.toString(),
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    override def jsonEncoder: Encoder[String] = implicitly

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next
  }

  object RegisterAppProviderTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[RegisterAppProviderTransaction]

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[RegisterAppProviderTransaction, Address] = GenLens[RegisterAppProviderTransaction](_.source)
    val _Destination: Lens[RegisterAppProviderTransaction, Address] = GenLens[RegisterAppProviderTransaction](_.destination)

    val _Amount: Lens[RegisterAppProviderTransaction, TransactionAmount] = GenLens[RegisterAppProviderTransaction](_.amount)
    val _AppIdentifier: Lens[RegisterAppProviderTransaction, String] = GenLens[RegisterAppProviderTransaction](_.appIdentifier)
    val _Host: Lens[RegisterAppProviderTransaction, String] = GenLens[RegisterAppProviderTransaction](_.host)
    val _Port: Lens[RegisterAppProviderTransaction, String] = GenLens[RegisterAppProviderTransaction](_.port)
    val _Fee: Lens[RegisterAppProviderTransaction, TransactionFee] = GenLens[RegisterAppProviderTransaction](_.fee)

    val _Parent: Lens[RegisterAppProviderTransaction, TransactionReference] = GenLens[RegisterAppProviderTransaction](_.parent)
    val _ParentHash: Lens[RegisterAppProviderTransaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[RegisterAppProviderTransaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

  @derive(decoder, encoder, order, show)
  case class DeployAppTransaction(
    source: Address,
    destination: Address,
    binaryHash: String,
    appName: String,
    appVersion: String,
    appDescription: String,
    appDownloadURL: String,
    fee: TransactionFee,
    amount: TransactionAmount,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction
      with Encodable[String] {

    import DeployAppTransaction._

    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            binaryHash,
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    override def jsonEncoder: Encoder[String] = implicitly

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next
  }

  object DeployAppTransaction {

    implicit object OrderingInstance extends OrderBasedOrdering[DeployAppTransaction]

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[DeployAppTransaction, Address] = GenLens[DeployAppTransaction](_.source)
    val _Destination: Lens[DeployAppTransaction, Address] = GenLens[DeployAppTransaction](_.destination)

    val _Amount: Lens[DeployAppTransaction, TransactionAmount] = GenLens[DeployAppTransaction](_.amount)
    val _BinaryHash: Lens[DeployAppTransaction, String] = GenLens[DeployAppTransaction](_.binaryHash)
    val _AppName: Lens[DeployAppTransaction, String] = GenLens[DeployAppTransaction](_.appName)
    val _AppVersion: Lens[DeployAppTransaction, String] = GenLens[DeployAppTransaction](_.appVersion)
    val _AppDescription: Lens[DeployAppTransaction, String] = GenLens[DeployAppTransaction](_.appDescription)
    val _AppDownloadURL: Lens[DeployAppTransaction, String] = GenLens[DeployAppTransaction](_.appDownloadURL)
    val _Fee: Lens[DeployAppTransaction, TransactionFee] = GenLens[DeployAppTransaction](_.fee)

    val _Parent: Lens[DeployAppTransaction, TransactionReference] = GenLens[DeployAppTransaction](_.parent)
    val _ParentHash: Lens[DeployAppTransaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[DeployAppTransaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

  @derive(decoder, encoder, order, show)
  case class DeployAppTransactionInfo(
    source: Address,
    appName: String,
    appVersion: String,
    appDescription: String,
    appDownloadURL: String,
    binaryHash: String
  )

  @derive(decoder, encoder, order, show)
  case class RegisterAppProviderTransactionInfo(
    source: Address,
    host: String,
    port: String,
    appIdentifier: String
  )

  @derive(decoder, encoder, order, show)
  case class RecordDataTransaction(
    source: Address,
    destination: Address,
    dataHash: String,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction
      with Encodable[String] {

    import RecordDataTransaction._

    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            dataHash,
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    override def jsonEncoder: Encoder[String] = implicitly

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next

  }

  object RecordDataTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[RecordDataTransaction]

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[RecordDataTransaction, Address] = GenLens[RecordDataTransaction](_.source)
    val _Destination: Lens[RecordDataTransaction, Address] = GenLens[RecordDataTransaction](_.destination)

    val _Amount: Lens[RecordDataTransaction, TransactionAmount] = GenLens[RecordDataTransaction](_.amount)
    val _DataHash: Lens[RecordDataTransaction, String] = GenLens[RecordDataTransaction](_.dataHash)

    val _Fee: Lens[RecordDataTransaction, TransactionFee] = GenLens[RecordDataTransaction](_.fee)

    val _Parent: Lens[RecordDataTransaction, TransactionReference] = GenLens[RecordDataTransaction](_.parent)
    val _ParentHash: Lens[RecordDataTransaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[RecordDataTransaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

}
