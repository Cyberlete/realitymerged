package org.reality.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.reality.dag.block.processing._
import org.reality.dag.domain.block.{NETBlock, NETBlockAsActiveTip}
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.domain.rewards.Rewards
import org.reality.domain.snapshot._
import org.reality.ext.cats.syntax.next._
import org.reality.ext.crypto._
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.balance.{Amount, Balance}
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction._
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.AppEnvironment.Mainnet
import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.reality.sdk.infrastructure.consensus.SelectActivePeers
import org.reality.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor
import org.reality.sdk.infrastructure.trust.TrustModel.entropyRate
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput
import org.reality.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops.toCoercibleIdOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotConsensusFunctions[F[_]]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: SecurityProvider: Metrics](
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    blockAcceptanceManager: BlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount,
    rewards: Rewards[F],
    environment: AppEnvironment
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def extractNextValidators(context: GlobalSnapshotContext): F[(GlobalSnapshotKey, NonEmptySet[PeerId])] =
      context.nextRotationFacilitators.pure[F]

    def consumeSignedMajorityArtifact(signedArtifact: Signed[GlobalSnapshotArtifact], context: GlobalSnapshotContext): F[Unit] =
      globalSnapshotStorage
        .prepend(signedArtifact, context)
        .ifM(
          metrics.globalSnapshot(signedArtifact),
          logger.error("Cannot save GlobalSnapshot into the storage")
        )

    def triggerPredicate(
      event: GlobalSnapshotEvent
    ): Boolean = true // placeholder for triggering based on fee

    private def extractEvents(artifact: GlobalSnapshotArtifact): Set[Either[StateChannelOutput, Signed[NETBlock]]] = {
      val netEvents = artifact.blocks.unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[NETEvent]).toList
      }

      netEvents ++ scEvents
    }

    def validateArtifact(
      lastArtifact: GlobalSnapshotArtifact,
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      newCandidates: Set[PeerId],
      cycleHeightDiff: Long,
      facilitators: Set[PeerId],
      removed: Set[PeerId]
    )(
      artifact: GlobalSnapshotArtifact
    ): F[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] = {
      val events = extractEvents(artifact)
      val influenceMaps = artifact.influenceMaps
      val proposedBlocks = artifact.proposedBlocks.some

      createProposalArtifact(
        lastArtifact.ordinal,
        lastArtifact,
        lastContext,
        trigger,
        events,
        influenceMaps,
        newCandidates,
        facilitators,
        removed,
        proposedBlocks
      ).map {
        case (recreatedArtifact, context, _, _) =>
          if (recreatedArtifact === artifact)
            (artifact, context).asRight[InvalidArtifact]
          else
            ArtifactMismatch.asLeft[(GlobalSnapshotArtifact, GlobalSnapshotContext)]
      }
    }

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: GlobalSnapshotArtifact,
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent],
      influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]], // TODO: validate before using
      newCandidates: Set[PeerId],
      facilitators: Set[PeerId],
      removed: Set[PeerId],
      maybeProposalBlocks: Option[SortedMap[PeerId, Signed[SortedSet[Hash]]]]
    ): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent], SortedSet[Hash])] = {
      val (scEvents: List[StateChannelEvent], netEvents: List[NETEvent]) = events.filter { event =>
        if (environment == Mainnet) event.isRight else true
      }.toList.partitionMap(identity)

      val blocksForAcceptance = netEvents
        .filter(_.height > lastArtifact.height)

      for {
        lastArtifactHash <- lastArtifact.hashF
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }
        (scSnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(lastContext, scEvents)
        sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
        context: BlockAcceptanceContext[F] = BlockAcceptanceContext.fromStaticData(
          lastContext.balances,
          lastContext.lastTxRefs,
          tipUsages,
          collateral
        )

        acceptanceResult: BlockAcceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        updatedLastTxRefs = lastContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

        balances = lastContext.balances ++ acceptanceResult.contextUpdate.balances
        positiveBalances = balances.filter { case (_, balance) => balance =!= Balance.empty }

        transactions = lastArtifact.blocks.flatMap(_.block.transactions.toSortedSet).map(_.value)
        entRates: Map[PeerId, Double] = entropyRate(lastArtifact.proposedBlocks.view.mapValues(_.value.toSeq).toMap)

        facilitatorsToReward = SortedSet.from(facilitators.map(PeerId._Id.get))
        rewardTxsForAcceptance <- rewards.feeDistribution(lastArtifact.ordinal, transactions, facilitatorsToReward, entRates).flatMap {
          feeRewardTxs =>
            trigger match {
              case EventTrigger => feeRewardTxs.pure[F]
              case TimeTrigger =>
                rewards
                  .mintedDistribution(lastArtifact.epochProgress, facilitatorsToReward, entRates)
                  .map(_ ++ feeRewardTxs)
            }
        }

        (newDeployAppTransactionsInfo, newRegisterAppProviderTransactionsInfo) = accepted
          .flatMap(_.block.value.transactions.map(_.value).toNonEmptyList.toList)
          .foldLeft((List.empty[DeployAppTransactionInfo], List.empty[RegisterAppProviderTransactionInfo])) { (acc, tx) =>
            tx match {
              case tx: DeployAppTransaction =>
                (
                  DeployAppTransactionInfo(
                    tx.source,
                    tx.appName,
                    tx.appVersion,
                    tx.appDescription,
                    tx.appDownloadURL,
                    tx.binaryHash
                  ) :: acc._1,
                  acc._2
                )
              case tx: RegisterAppProviderTransaction =>
                (
                  acc._1,
                  RegisterAppProviderTransactionInfo(
                    tx.source,
                    tx.host,
                    tx.port,
                    tx.appIdentifier
                  ) :: acc._2
                )
              case _ => acc
            }
          }

        deployAppTransactionsInfo = lastContext.deployAppTransactionsInfo ++ newDeployAppTransactionsInfo
          .map(txInfo => s"${txInfo.appName}-${txInfo.appVersion}-${txInfo.source.coerce.value}" -> txInfo)
          .toMap

        registerAppProviderTransactionsInfo = lastContext.registerAppProviderTransactionsInfo ++ newRegisterAppProviderTransactionsInfo
          .map(txInfo => txInfo.appIdentifier -> txInfo)
          .toMap

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(positiveBalances, rewardTxsForAcceptance)

        acceptedBlockHashes <- acceptanceResult.accepted.traverse(_._1.toHashed).map(hs => SortedSet.from(hs.map(_.hash)))
        returnedNETEvents = getReturnedNETEvents(acceptanceResult)

        candidatesAfterRemoval = lastContext.candidates -- removed
        candidatesToAdd = newCandidates -- candidatesAfterRemoval
        updatedCandidates = candidatesAfterRemoval ++ candidatesToAdd
        candidatesBalances <- updatedCandidates.toList.traverse { peerId =>
          peerId.toAddress.map(peerId -> updatedBalancesByRewards.getOrElse(_, Balance.empty).value)
        }.map(_.toMap)
        nextRotationFacilitators =
          if (currentOrdinal.value % SelectActivePeers.selectionInterval == 0) {
            val selected = SelectActivePeers
              .selectPeers(
                lastArtifactHash,
                influenceMaps.view.mapValues(_.value).toMap,
                candidatesBalances
              )

            val nextRangeStart =
              currentOrdinal.nextN(NonNegLong.unsafeFrom(SelectActivePeers.selectionInterval.value))

            // TODO: we shouldn't ever not have a facilitator back from the calculator
            NonEmptySet
              .fromSet(SortedSet.from(selected))
              .map((nextRangeStart, _))
              .getOrElse((nextRangeStart, lastContext.nextRotationFacilitators._2))
          } else lastContext.nextRotationFacilitators
        globalSnapshotInfo = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          updatedLastTxRefs,
          updatedBalancesByRewards,
          deployAppTransactionsInfo,
          registerAppProviderTransactionsInfo,
          candidates = updatedCandidates,
          nextRotationFacilitators
        )
        stateProof <- globalSnapshotInfo.stateProof
        globalSnapshot = GlobalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          scSnapshots,
          acceptedRewardTxs,
          currentEpochProgress,
          removed = SortedSet.from(removed),
          addedCandidates = SortedSet.from(candidatesToAdd),
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof,
          influenceMaps,
          maybeProposalBlocks.getOrElse(SortedMap.empty)
        )

        returnedEvents = returnedSCEvents.map(_.asLeft[NETEvent]).union(returnedNETEvents)
      } yield (globalSnapshot, globalSnapshotInfo, returnedEvents, acceptedBlockHashes)
    }

    def recalculateArtifactWithNewMetadata(
      lastKey: GlobalSnapshotKey,
      lastArtifact: GlobalSnapshotArtifact,
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      facilitators: Set[PeerId],
      influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]],
      proposalBlocks: SortedMap[PeerId, Signed[SortedSet[Hash]]]
    )(artifact: GlobalSnapshotArtifact): F[(Hash, GlobalSnapshotArtifact, GlobalSnapshotContext)] = {
      val events = extractEvents(artifact)

      createProposalArtifact(
        lastKey,
        lastArtifact,
        lastContext,
        trigger,
        events,
        influenceMaps,
        newCandidates = artifact.addedCandidates,
        facilitators = facilitators,
        removed = artifact.removed,
        proposalBlocks.some
      ).flatMap {
        case (artifact, context, _, _) =>
          artifact.hashF.map((_, artifact, context))
      }
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

    private def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

    private def getUpdatedTips(
      lastActive: SortedSet[ActiveTip],
      lastDeprecated: SortedSet[DeprecatedTip],
      acceptanceResult: BlockAcceptanceResult,
      currentOrdinal: GlobalSnapshotKey
    ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[NETBlockAsActiveTip]) = {
      val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
      val accepted =
        acceptanceResult.accepted.map { case (block, usages) => NETBlockAsActiveTip(block, usages) }.toSortedSet
      val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
        val maybeUpdatedUsage = usagesUpdate.get(at.block)
        Either.cond(
          maybeUpdatedUsage.exists(_ >= deprecationThreshold),
          DeprecatedTip(at.block, currentOrdinal),
          maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
        )
      }.bimap(_.toSortedSet, _.toSortedSet)
      val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
      val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

      (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
    }

    private def getHeightAndSubHeight(
      lastGS: GlobalSnapshot,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[NETBlockAsActiveTip]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastGS.height)
            InvalidHeight(lastGS.height, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    private def getReturnedNETEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet

    case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
    case object NoTipsRemaining extends NoStackTrace

    case object ArtifactMismatch extends InvalidArtifact

    object metrics {

      def globalSnapshot(signedGS: Signed[GlobalSnapshot]): F[Unit] = {
        val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
        val deprecatedTipsCount = signedGS.tips.deprecated.size
        val transactionCount = signedGS.blocks.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("net_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("net_global_snapshot_height", signedGS.height.value) >>
          Metrics[F].updateGauge("net_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F]
            .updateGauge("net_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("net_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("net_global_snapshot_blocks_total", signedGS.blocks.size) >>
          Metrics[F].incrementCounterBy("net_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("net_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }

  }
}
