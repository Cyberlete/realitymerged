package org.reality.net.domain.block

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.SortedSet
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.generators._
import org.reality.schema.transaction.{RewardTransaction, TransactionAmount}
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.reality.dag.domain.block.{NETBlock, NETBlockAsActiveTip}
import org.reality.dag.snapshot.GlobalSnapshotStateProof
import org.scalacheck.{Arbitrary, Gen}

object generators {

  val blockReferencesGen: Gen[NonEmptyList[BlockReference]] =
    Gen.nonEmptyListOf(Arbitrary.arbitrary[BlockReference]).map(NonEmptyList.fromListUnsafe(_))

  val netBlockGen: Gen[NETBlock] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedTransactionGen
    } yield NETBlock(blockReferences, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val signedNETBlockGen: Gen[Signed[NETBlock]] = signedOf(netBlockGen)
  implicit val signedNETBlockArbitrary = Arbitrary(signedNETBlockGen)

  val snapshotOrdinalGen: Gen[SnapshotOrdinal] = Arbitrary.arbitrary[NonNegLong].map(SnapshotOrdinal(_))
  implicit val snapshotOrdinalArbitrary = Arbitrary(snapshotOrdinalGen)

  implicit val netBlockAsActiveTipGen = for {
    netBlock <- Arbitrary.arbitrary[Signed[NETBlock]]
    usage <- Arbitrary.arbitrary[NonNegLong]
  } yield NETBlockAsActiveTip(netBlock, usage)
  implicit val netBlockAsActiveTipArbitrary = Arbitrary(netBlockAsActiveTipGen)

  val transactionAmountGen = Arbitrary.arbitrary[PosLong].map(TransactionAmount(_))
  implicit val transactionAmountArbitrary = Arbitrary(transactionAmountGen)

  val rewardTransactionGen: Gen[RewardTransaction] = for {
    address <- Arbitrary.arbitrary[Address]
    amount <- Arbitrary.arbitrary[TransactionAmount]
  } yield RewardTransaction(address, amount)
  implicit val rewardTransactionArbitrary = Arbitrary(rewardTransactionGen)

  val deprecatedTipGen: Gen[DeprecatedTip] = for {
    blockReference <- Arbitrary.arbitrary[BlockReference]
    snapshotOrdinal <- Arbitrary.arbitrary[SnapshotOrdinal]
  } yield DeprecatedTip(blockReference, snapshotOrdinal)
  implicit val deprecatedTipArbitrary = Arbitrary(deprecatedTipGen)

  val activeTipGen: Gen[ActiveTip] = for {
    blockReference <- Arbitrary.arbitrary[BlockReference]
    usageCount <- Arbitrary.arbitrary[NonNegLong]
    snapshotOrdinal <- Arbitrary.arbitrary[SnapshotOrdinal]
  } yield ActiveTip(blockReference, usageCount, snapshotOrdinal)
  implicit val activeTipArbitrary = Arbitrary(activeTipGen)

  val snapshotTipsGen: Gen[SnapshotTips] = for {
    deprecatedTips <- Arbitrary.arbitrary[SortedSet[DeprecatedTip]]
    activeTips <- Arbitrary.arbitrary[SortedSet[ActiveTip]]
  } yield SnapshotTips(deprecatedTips, activeTips)
  implicit val snapshotTipsArbitrary = Arbitrary(snapshotTipsGen)

  val globalSnapshotStateProofGen = for {
    hash1 <- Arbitrary.arbitrary[Hash]
    hash2 <- Arbitrary.arbitrary[Hash]
    hash3 <- Arbitrary.arbitrary[Hash]
    hash4 <- Arbitrary.arbitrary[Hash]
    hash5 <- Arbitrary.arbitrary[Hash]
    hash6 <- Arbitrary.arbitrary[Hash]
    hash7 <- Arbitrary.arbitrary[Hash]
  } yield GlobalSnapshotStateProof(hash1, hash2, hash3, hash4, hash5, hash6, hash7)
  implicit val globalSnapshotStateProofArbitrary = Arbitrary(globalSnapshotStateProofGen)

}
