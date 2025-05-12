package org.reality.combined.node

import java.lang.Integer.parseInt
import java.util.Base64

import cats.effect.{Async, IO}

import org.reality.L0Internals
import org.reality.combined.EnvUtil.envVarOrError
import org.reality.dag.l1.L1Internals
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.sdk.app.NodeInternals
import org.reality.security.SecurityProvider
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed

import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import io.circe.Json
import io.circe.generic.codec.DerivedAsObjectCodec.deriveCodec
import io.ipfs.api.IPFS
import io.ipfs.api.NamedStreamable.ByteArrayWrapper

case class SendRecordDataTransactionParams(
  destination: Address,
  fee: TransactionFee,
  data: String
)

class NodeApi(
  val l0Internals: L0Internals,
  val l1Internals: L1Internals
)(implicit val A: Async[IO]) {

  def handleAction(
    action: String,
    params: Json
  ): IO[Json] =
    action match {
      case "sendRecordDataTransaction" =>
        params.as[SendRecordDataTransactionParams] match {
          case Right(validParams) => sendRecordDataTransaction(validParams)
          case Left(error)        => IO.raiseError(new Exception(s"Invalid params: $error"))
        }
      case _ => IO.raiseError(new Exception("Unsupported action"))
    }

  private def sendRecordDataTransaction(
    params: SendRecordDataTransactionParams
  ): IO[Json] = {
    val nodePublicAddress = l1Internals.sdk.keyPair.getPublic.toAddress
    val dataBytes = Base64.getDecoder.decode(params.data)
    val amount = TransactionAmount(PosLong.MinValue)
    val fee = TransactionFee(NonNegLong.MinValue)

    implicit val securityProvider: SecurityProvider[IO] = l1Internals.sdk.securityProvider

    for {
      ipfsHost <- envVarOrError("IPFS_HOST")
      ipfsPort <- envVarOrError("IPFS_PORT")
      ipfsClient = new IPFS(ipfsHost, parseInt(ipfsPort))
      file = new ByteArrayWrapper("", dataBytes)
      addResult = ipfsClient.add(file).get(0).toString
      salt <- l1Internals.sdk.random.nextLong.map(TransactionSalt.apply)
      parent <- l1Internals.storages.transaction.getLastAcceptedReference(nodePublicAddress)
      recordDataTransaction = RecordDataTransaction(nodePublicAddress, params.destination, addResult, amount, fee, parent, salt)
      keyPair = l1Internals.sdk.keyPair
      signedTx <- Signed.forAsyncJson(recordDataTransaction, keyPair)
      hashedTx <- signedTx.toHashed
      _ <- l1Internals.services.transaction.offer(hashedTx)
    } yield
      Json.obj(
        "status" -> Json.fromString("Transaction Sent"),
        "transactionHash" -> Json.fromString(hashedTx.hash.toString),
        "dataHash" -> Json.fromString(addResult)
      )

  }.handleErrorWith {
    case e: NullPointerException =>
      IO.raiseError(
        new Exception(s"Null Pointer Exception: ${e.getMessage}. Stack trace: ${e.getStackTrace.mkString("\n")}")
      )
    case otherException =>
      IO.raiseError(
        new Exception(
          s"Another exception occurred: ${otherException.getMessage}. Stack trace: ${otherException.getStackTrace.mkString("\n")}"
        )
      )
  }

}

object NodeApi {
  def make(l0Bootstrap: NodeInternals, l1Bootstrap: NodeInternals)(implicit A: Async[IO]): IO[NodeApi] =
    IO.pure(new NodeApi(l0Bootstrap.asInstanceOf[L0Internals], l1Bootstrap.asInstanceOf[L1Internals]))
}
