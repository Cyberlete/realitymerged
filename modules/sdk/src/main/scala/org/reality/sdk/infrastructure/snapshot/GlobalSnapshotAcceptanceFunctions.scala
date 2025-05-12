package org.reality.sdk.infrastructure.snapshot

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.reality.dag.block.processing._
import org.reality.dag.domain.block.NETBlock
import org.reality.dag.snapshot.{GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.reality.ext.cats.syntax.next.catsSyntaxNext
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.balance.{Amount, Balance}
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction._
import org.reality.sdk.infrastructure.consensus.SelectActivePeers
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.reality.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops.toCoercibleIdOps

trait GlobalSnapshotAcceptanceFunctions[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    lastArtifactHash: Hash,
    blocksForAcceptance: List[Signed[NETBlock]],
    scEvents: List[StateChannelOutput],
    influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    newCandidates: SortedSet[PeerId],
    removed: SortedSet[PeerId]
  ): F[
    (
      BlockAcceptanceResult,
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof
    )
  ]
}

object GlobalSnapshotAcceptanceFunctions {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: SecurityProvider](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceFunctions[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      lastArtifactHash: Hash,
      blocksForAcceptance: List[Signed[NETBlock]],
      scEvents: List[StateChannelOutput],
      influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      newCandidates: SortedSet[PeerId],
      removed: SortedSet[PeerId]
    ) = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      (scSnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(lastSnapshotContext, scEvents)
      sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.toHashed.map(address -> _.hash) }
        .map(_.toMap)
      updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances,
        rewards
      )

      (newDeployAppTransactionsInfo, newRegisterAppProviderTransactionsInfo) = acceptanceResult.accepted
        .flatMap(_._1.value.transactions.map(_.value).toNonEmptyList.toList)
        .foldLeft((List.empty[DeployAppTransactionInfo], List.empty[RegisterAppProviderTransactionInfo])) { (acc, tx) =>
          tx match {
            case tx: DeployAppTransaction =>
              (
                DeployAppTransactionInfo(tx.source, tx.appName, tx.appVersion, tx.appDescription, tx.appDownloadURL, tx.binaryHash)
                  :: acc._1,
                acc._2
              )
            case tx: RegisterAppProviderTransaction =>
              (acc._1, RegisterAppProviderTransactionInfo(tx.source, tx.host, tx.port, tx.appIdentifier) :: acc._2)
            case _ => acc
          }
        }

      deployAppTransactionsInfo = lastSnapshotContext.deployAppTransactionsInfo ++ newDeployAppTransactionsInfo
        .map(tx => s"${tx.appName}-${tx.appVersion}-${tx.source.coerce.value}" -> tx)
        .toMap
      registerAppProviderTransactionsInfo =
        lastSnapshotContext.registerAppProviderTransactionsInfo ++ newRegisterAppProviderTransactionsInfo
          .map(tx => tx.appIdentifier -> tx)
          .toMap

      candidatesAfterRemoval = lastSnapshotContext.candidates -- removed
      candidatesToAdd = newCandidates -- candidatesAfterRemoval
      updatedCandidates = candidatesAfterRemoval ++ candidatesToAdd
      candidatesBalances <- updatedCandidates.toList.traverse { peerId =>
        peerId.toAddress.map(peerId -> updatedBalancesByRewards.getOrElse(_, Balance.empty).value)
      }.map(_.toMap)
      nextRotationFacilitators =
        if (ordinal.value % SelectActivePeers.selectionInterval == 0) {
          val selected = SelectActivePeers
            .selectPeers(
              lastArtifactHash,
              influenceMaps.view.mapValues(_.value).toMap,
              candidatesBalances
            )

          val nextRangeStart =
            ordinal.nextN(NonNegLong.unsafeFrom(SelectActivePeers.selectionInterval.value))

          // TODO: we shouldn't ever not have a facilitator back from the calculator
          NonEmptySet
            .fromSet(SortedSet.from(selected))
            .map((nextRangeStart, _))
            .getOrElse((nextRangeStart, lastSnapshotContext.nextRotationFacilitators._2))
        } else lastSnapshotContext.nextRotationFacilitators

      gsi = GlobalSnapshotInfo(
        updatedLastStateChannelSnapshotHashes,
        transactionsRefs,
        updatedBalancesByRewards,
        deployAppTransactionsInfo,
        registerAppProviderTransactionsInfo,
        candidates = updatedCandidates,
        nextRotationFacilitators = nextRotationFacilitators
      )

      stateProof <- gsi.stateProof

    } yield
      (
        acceptanceResult,
        scSnapshots,
        returnedSCEvents,
        acceptedRewardTxs,
        gsi,
        stateProof
      )

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[NETBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

  }

}
