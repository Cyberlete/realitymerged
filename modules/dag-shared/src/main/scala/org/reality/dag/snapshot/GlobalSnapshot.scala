package org.reality.dag.snapshot

import cats.MonadThrow
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.dag.domain.block.NETBlockAsActiveTip
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.balance.Balance
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.peer.PeerId
import org.reality.schema.semver.SnapshotVersion
import org.reality.schema.transaction.RewardTransaction
import org.reality.security.hash.{Hash, ProofsHash}
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelSnapshotBinary
import org.reality.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

import epoch.EpochProgress

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[NETBlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  removed: SortedSet[PeerId],
  addedCandidates: SortedSet[PeerId],
  tips: SnapshotTips,
  stateProof: GlobalSnapshotStateProof,
  // TODO: implicitly add ordinal before signing the influ map
  // TODO: only include the maps on the snapshot where facilitators are picked
  influenceMaps: SortedMap[PeerId, Signed[SortedMap[PeerId, Double]]] = SortedMap.empty,
  proposedBlocks: SortedMap[PeerId, Signed[SortedSet[Hash]]] = SortedMap.empty,
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) {

  def activeTips[F[_]: Async]: F[SortedSet[ActiveTip]] =
    blocks.toList.traverse { blockAsActiveTip =>
      BlockReference
        .of(blockAsActiveTip.block)
        .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
    }.map(_.toSortedSet.union(tips.remainedActive))

}

object GlobalSnapshot {

  def mkGenesis[F[_]: MonadThrow](
    balances: Map[Address, Balance],
    startingEpochProgress: EpochProgress,
    nodeId: PeerId
  ): F[(GlobalSnapshot, GlobalSnapshotInfo)] = {
    val snapshotInfo =
      GlobalSnapshotInfo(
        SortedMap.empty,
        SortedMap.empty,
        SortedMap.from(balances),
        SortedMap.empty,
        SortedMap.empty,
        candidates = SortedSet(nodeId),
        (SnapshotOrdinal(20L), NonEmptySet.one(nodeId)) // TODO: take from SelectActivePeers or some common config
      )

    snapshotInfo.stateProof.map { stateProof =>
      val globalSnapshot = GlobalSnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Coinbase.hash,
        SortedSet.empty,
        SortedMap.empty,
        SortedSet.empty,
        startingEpochProgress,
        removed = SortedSet.empty,
        addedCandidates = SortedSet(nodeId),
        SnapshotTips(
          SortedSet.empty[DeprecatedTip],
          mkActiveTips(8)
        ),
        stateProof
      )
      (globalSnapshot, snapshotInfo)
    }
  }

  private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
    List
      .range(0, n.value)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSortedSet

}
