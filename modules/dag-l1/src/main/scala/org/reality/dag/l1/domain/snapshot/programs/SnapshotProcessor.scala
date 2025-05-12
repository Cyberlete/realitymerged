package org.reality.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.reality.dag.domain.block.{NETBlock, NETBlockAsActiveTip}
import org.reality.dag.l1.domain.address.storage.AddressStorage
import org.reality.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.reality.dag.l1.domain.block.{BlockRelations, BlockStorage}
import org.reality.dag.l1.domain.transaction.TransactionStorage
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo, GlobalSnapshotReference}
import org.reality.kernel.Ω
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.transaction.TransactionReference
import org.reality.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.reality.sdk.domain.snapshot.{SnapshotContextFunctions, Validator}
import org.reality.security.hash.ProofsHash
import org.reality.security.signature.Signed
import org.reality.security.{Hashed, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotProcessor {

  def make[F[_]: Async: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalSnapshot, GlobalSnapshotInfo]
  ): SnapshotProcessor[F] =
    new SnapshotProcessor[F](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage, globalSnapshotContextFns) {}

  sealed trait Alignment
  case class AlignedAtNewOrdinal(
    snapshot: Hashed[GlobalSnapshot],
    state: GlobalSnapshotInfo,
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class AlignedAtNewHeight(
    snapshot: Hashed[GlobalSnapshot],
    state: GlobalSnapshotInfo,
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class DownloadNeeded(
    snapshot: Hashed[GlobalSnapshot],
    state: GlobalSnapshotInfo,
    toAdd: Set[(Hashed[NETBlock], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class RedownloadNeeded(
    snapshot: Hashed[GlobalSnapshot],
    state: GlobalSnapshotInfo,
    toAdd: Set[(Hashed[NETBlock], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class Ignore(
    snapshot: Hashed[GlobalSnapshot],
    lastHeight: Height,
    lastSubHeight: SubHeight,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingSubHeight: SubHeight,
    processingOrdinal: SnapshotOrdinal
  ) extends Alignment

  @derive(show)
  sealed trait SnapshotProcessingResult extends Ω
  case class Aligned(
    reference: GlobalSnapshotReference,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class DownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class RedownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class SnapshotIgnored(
    reference: GlobalSnapshotReference
  ) extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash]) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}

sealed abstract class SnapshotProcessor[F[_]: Async: SecurityProvider] private (
  addressStorage: AddressStorage[F],
  blockStorage: BlockStorage[F],
  lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
  transactionStorage: TransactionStorage[F],
  globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalSnapshot, GlobalSnapshotInfo]
) {

  import SnapshotProcessor._

  def logger = Slf4jLogger.getLogger[F]

  private def applyGlobalSnapshotFn(
    lastGlobalState: GlobalSnapshotInfo,
    lastGlobalSnapshot: Signed[GlobalSnapshot],
    globalSnapshot: Signed[GlobalSnapshot]
  ): F[GlobalSnapshotInfo] = globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot)

  def process(globalSnapshot: Either[(Hashed[GlobalSnapshot], GlobalSnapshotInfo), Hashed[GlobalSnapshot]]): F[SnapshotProcessingResult] =
    checkAlignment(globalSnapshot).flatMap {
      case AlignedAtNewOrdinal(snapshot, state, toMarkMajority, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority, postponedToWaiting) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove,
              postponedToWaiting = postponedToWaiting
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              Set.empty
            )
          }

      case AlignedAtNewHeight(
            snapshot,
            state,
            toMarkMajority,
            obsoleteToRemove,
            tipsToDeprecate,
            tipsToRemove,
            txRefsToMarkMajority,
            postponedToWaiting
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              obsoleteToRemove = obsoleteToRemove,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove,
              postponedToWaiting = postponedToWaiting
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              obsoleteToRemove
            )
          }

      case DownloadNeeded(snapshot, state, toAdd, obsoleteToRemove, activeTips, deprecatedTips, relatedPostponed) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            obsoleteToRemove = obsoleteToRemove,
            activeTipsToAdd = activeTips,
            deprecatedTipsToAdd = deprecatedTips,
            postponedToWaiting = relatedPostponed
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(state.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(state.lastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.setInitial(snapshot, state)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot.as[SnapshotProcessingResult] {
            DownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              obsoleteToRemove
            )
          }

      case RedownloadNeeded(
            snapshot,
            state,
            toAdd,
            toMarkMajority,
            acceptedToRemove,
            obsoleteToRemove,
            toReset,
            tipsToDeprecate,
            tipsToRemove,
            postponedToWaiting
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            toMarkMajority = toMarkMajority,
            acceptedToRemove = acceptedToRemove,
            obsoleteToRemove = obsoleteToRemove,
            toReset = toReset,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove,
            postponedToWaiting = postponedToWaiting
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(state.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(state.lastTxRefs)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot.as[SnapshotProcessingResult] {
            RedownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }

      case Ignore(snapshot, lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
        Slf4jLogger
          .getLogger[F]
          .warn(
            s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
          )
          .as(SnapshotIgnored(GlobalSnapshotReference.fromHashedGlobalSnapshot(snapshot)))
    }

  private def checkAlignment(
    snapshotWithState: Either[(Hashed[GlobalSnapshot], GlobalSnapshotInfo), Hashed[GlobalSnapshot]]
  ): F[Alignment] = {
    snapshotWithState
      .fold({ case (snapshot, _) => snapshot }, identity)
      .blocks
      .toList
      .traverse {
        case NETBlockAsActiveTip(block, usageCount) =>
          block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }
      .map(_.toMap)
      .flatMap { acceptedInMajority =>
        snapshotWithState match {
          case Left((snapshot, state)) =>
            val SnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case None =>
                val isDependent = (block: Signed[NETBlock]) =>
                  BlockRelations.dependsOn[F](
                    acceptedInMajority.values.map(_._1).toSet,
                    snapshotDeprecatedTips.map(_.block) ++ snapshotRemainedActive.map(_.block)
                  )(block)

                blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, snapshot.height, isDependent).flatMap {
                  case MajorityReconciliationData(_, _, waitingInRange, postponedInRange, relatedPostponed, _, _) =>
                    val initialBlocksAndTips =
                      acceptedInMajority.keySet ++ snapshotRemainedActive.map(_.block.hash) ++ snapshotDeprecatedTips.map(_.block.hash)
                    val obsoleteToRemove = waitingInRange ++ postponedInRange -- initialBlocksAndTips
                    val postponedToWaiting = relatedPostponed -- postponedInRange -- initialBlocksAndTips

                    Applicative[F].pure[Alignment](
                      DownloadNeeded(
                        snapshot,
                        state,
                        acceptedInMajority.values.toSet,
                        obsoleteToRemove,
                        snapshotRemainedActive,
                        snapshotDeprecatedTips.map(_.block),
                        postponedToWaiting
                      )
                    )
                }
              case _ => (new Throwable("unexpected state: latest snapshot found")).raiseError[F, Alignment]
            }
          case Right(snapshot) =>
            val SnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case Some((lastSnapshot, lastState)) =>
                Validator.compare(lastSnapshot, snapshot.signed.value) match {
                  case Validator.NextSubHeight =>
                    val isDependent =
                      (block: Signed[NETBlock]) => BlockRelations.dependsOn[F](acceptedInMajority.values.map(_._1).toSet)(block)

                    applyGlobalSnapshotFn(lastState, lastSnapshot.signed, snapshot.signed).flatMap { state =>
                      blockStorage.getBlocksForMajorityReconciliation(lastSnapshot.height, snapshot.height, isDependent).flatMap {
                        case MajorityReconciliationData(deprecatedTips, activeTips, _, _, relatedPostponed, _, acceptedAbove) =>
                          val onlyInMajority = acceptedInMajority -- acceptedAbove
                          val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                          lazy val toAdd = onlyInMajority.values.toSet
                          lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                          val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                          val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                          val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                          val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                          lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, state)
                          lazy val postponedToWaiting = relatedPostponed -- toAdd.map(_._1.proofsHash) -- toReset

                          if (!areTipsAligned)
                            MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                          else if (onlyInMajority.isEmpty)
                            Applicative[F].pure[Alignment](
                              AlignedAtNewOrdinal(
                                snapshot,
                                state,
                                toMarkMajority.toSet,
                                tipsToDeprecate,
                                tipsToRemove,
                                txRefsToMarkMajority,
                                postponedToWaiting
                              )
                            )
                          else
                            Applicative[F]
                              .pure[Alignment](
                                RedownloadNeeded(
                                  snapshot,
                                  state,
                                  toAdd,
                                  toMarkMajority.toSet,
                                  Set.empty,
                                  Set.empty,
                                  toReset,
                                  tipsToDeprecate,
                                  tipsToRemove,
                                  postponedToWaiting
                                )
                              )
                      }
                    }

                  case Validator.NextHeight =>
                    val isDependent =
                      (block: Signed[NETBlock]) => BlockRelations.dependsOn[F](acceptedInMajority.values.map(_._1).toSet)(block)

                    applyGlobalSnapshotFn(lastState, lastSnapshot.signed, snapshot.signed).flatMap { state =>
                      blockStorage.getBlocksForMajorityReconciliation(lastSnapshot.height, snapshot.height, isDependent).flatMap {
                        case MajorityReconciliationData(
                              deprecatedTips,
                              activeTips,
                              waitingInRange,
                              postponedInRange,
                              relatedPostponed,
                              acceptedInRange,
                              acceptedAbove
                            ) =>
                          val acceptedLocally = acceptedInRange ++ acceptedAbove
                          val onlyInMajority = acceptedInMajority -- acceptedLocally
                          val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedLocally.contains).mapValues(_._2)
                          val acceptedToRemove = acceptedInRange -- acceptedInMajority.keySet
                          lazy val toAdd = onlyInMajority.values.toSet
                          lazy val toReset = acceptedLocally -- toMarkMajority.keySet -- acceptedToRemove
                          val obsoleteToRemove = waitingInRange ++ postponedInRange -- onlyInMajority.keySet
                          val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                          val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                          val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                          val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                          lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, state)
                          lazy val postponedToWaiting = relatedPostponed -- obsoleteToRemove -- toAdd.map(_._1.proofsHash) -- toReset

                          if (!areTipsAligned)
                            MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                          else if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                            Applicative[F].pure[Alignment](
                              AlignedAtNewHeight(
                                snapshot,
                                state,
                                toMarkMajority.toSet,
                                obsoleteToRemove,
                                tipsToDeprecate,
                                tipsToRemove,
                                txRefsToMarkMajority,
                                postponedToWaiting
                              )
                            )
                          else
                            Applicative[F].pure[Alignment](
                              RedownloadNeeded(
                                snapshot,
                                state,
                                toAdd,
                                toMarkMajority.toSet,
                                acceptedToRemove,
                                obsoleteToRemove,
                                toReset,
                                tipsToDeprecate,
                                tipsToRemove,
                                postponedToWaiting
                              )
                            )
                      }
                    }

                  case Validator.NotNext =>
                    Applicative[F].pure[Alignment](
                      Ignore(
                        snapshot,
                        lastSnapshot.height,
                        lastSnapshot.subHeight,
                        lastSnapshot.ordinal,
                        snapshot.height,
                        snapshot.subHeight,
                        snapshot.ordinal
                      )
                    )
                }
              case None => (new Throwable("unexpected state: latest snapshot not found")).raiseError[F, Alignment]

            }
        }

      }
  }

  private def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[NETBlock], NonNegLong)],
    state: GlobalSnapshotInfo
  ): Map[Address, TransactionReference] = {
    val sourceAddresses =
      acceptedInMajority.values
        .flatMap(_._1.transactions.toSortedSet)
        .map(_.source)
        .toSet

    state.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
  }
}
