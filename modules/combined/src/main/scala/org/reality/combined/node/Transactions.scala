package org.reality.combined.node

import cats.effect.{Async, Sync}
import cats.effect.std.Random
import cats.implicits._
import org.reality.schema.address.Address
import org.reality.schema.transaction.{StandardTransaction, TransactionAmount, TransactionFee, TransactionReference}
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.tools.AddressParams

object Transactions {

  /** Creates and signs a transaction
    */
  def createSignedTransaction[F[_]: Async, T <: StandardTransaction](
    createTx: (Address, Address, TransactionAmount, TransactionFee, TransactionReference, Long) => T,
    sourceAddressParam: AddressParams,
    destinationAddress: Address,
    amount: TransactionAmount,
    fee: TransactionFee
  )(implicit random: Random[F], sp: SecurityProvider[F]): F[Signed[T]] = {
    // Extract transaction reference from source address
    val txRef = getTxReference(sourceAddressParam)

    for {
      // Generate salt
      salt <- random.nextLong

      // Create transaction
      tx = createTx(
        sourceAddressParam.address,
        destinationAddress,
        amount,
        fee,
        txRef,
        salt
      )

      // Sign the transaction
      signed <- signTransaction(tx, sourceAddressParam)
    } yield signed
  }

  // Helper method to get transaction reference from address params
  private def getTxReference[F[_]: Sync](params: AddressParams): TransactionReference = {
    // This is a placeholder - you need to implement based on your actual AddressParams structure
    // Example implementation - adjust based on your actual AddressParams structure
    val txRefValue = params.toString.split("@").lastOption.getOrElse("unknown")
    TransactionReference(txRefValue)
  }

  // Helper method to sign a transaction
  private def signTransaction[F[_]: Sync, T <: StandardTransaction](
    tx: T,
    sourceAddressParam: AddressParams
  )(implicit sp: SecurityProvider[F]): F[Signed[T]] =
    // Get the transaction hash first
    for {
      // Generate hash of the transaction
      txHash <- sp.hash(tx)

      // Sign the transaction with the private key from AddressParams
      signatureProof <- sp.sign(txHash, sourceAddressParam.privateKey)

      // Create a NonEmptySet with the signature proof
      proofs = NonEmptySet.of(signatureProof)
    } yield Signed(tx, proofs, txHash)
}
