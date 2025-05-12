package org.reality.sdk.infrastructure.snapshot

import cats.data.Validated
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.reality.dag.block.processing.BlockNotAcceptedReason
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo, StateProofValidator}
import org.reality.schema.transaction.RewardTransaction
import org.reality.sdk.domain.snapshot.SnapshotContextFunctions
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {
  def make[F[_]: Async](snapshotAcceptanceFunctions: GlobalSnapshotAcceptanceFunctions[F]) =
    new GlobalSnapshotContextFunctions[F] {
      def createContext(
        context: GlobalSnapshotInfo,
        lastArtifact: Signed[GlobalSnapshot],
        signedArtifact: Signed[GlobalSnapshot]
      ): F[GlobalSnapshotInfo] = for {
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        blocksForAcceptance = signedArtifact.blocks.toList.map(_.block)

        scEvents = signedArtifact.stateChannelSnapshots.toList.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
        }
        (acceptanceResult, scSnapshots, returnedSCEvents, acceptedRewardTxs, snapshotInfo, _) <- snapshotAcceptanceFunctions.accept(
          signedArtifact.ordinal,
          signedArtifact.lastSnapshotHash,
          blocksForAcceptance,
          scEvents,
          signedArtifact.influenceMaps,
          context,
          lastActiveTips,
          lastDeprecatedTips,
          _ => signedArtifact.rewards.pure[F],
          signedArtifact.addedCandidates,
          signedArtifact.removed
        )
        _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map { case (_, reason) => reason })
          .raiseError[F, Unit]
          .whenA(acceptanceResult.notAccepted.nonEmpty)
        _ <- CannotApplyStateChannelsError(returnedSCEvents).raiseError[F, Unit].whenA(returnedSCEvents.nonEmpty)
        diffRewards = acceptedRewardTxs -- signedArtifact.rewards
        _ <- CannotApplyRewardsError(diffRewards).raiseError[F, Unit].whenA(diffRewards.nonEmpty)
        _ <- StateProofValidator.validate(signedArtifact, snapshotInfo).flatMap {
          case Validated.Valid(_)   => Async[F].unit
          case Validated.Invalid(e) => e.raiseError[F, Unit]
        }

      } yield snapshotInfo
    }

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot ${reasons.show}"
  }

  @derive(eqv)
  case class CannotApplyStateChannelsError(returnedStateChannels: Set[StateChannelOutput]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of returned StateChannels for addresses: ${returnedStateChannels.map(_.address).show}"
  }

  @derive(eqv, show)
  case class CannotApplyRewardsError(notAcceptedRewards: SortedSet[RewardTransaction]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of not accepted rewards: ${notAcceptedRewards.show}"
  }
}
