package org.reality.dag.l1.domain.consensus.block

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import org.reality.dag.l1.domain.block.BlockStorage
import org.reality.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.reality.dag.l1.domain.transaction.TransactionStorage
import org.reality.schema.node.NodeState
import org.reality.schema.node.NodeState.Ready
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.node.NodeStorage
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt
import org.typelevel.log4cats.slf4j.Slf4jLogger

import BlockConsensusInput.PeerBlockConsensusInput

object Validator {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  def isReadyForBlockConsensus(state: NodeState): Boolean = state == Ready

  private def enoughPeersForConsensus[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getResponsivePeers
      .map(_.filter(p => isReadyForBlockConsensus(p.state)))
      .map(_.size >= peersCount)

  private def enoughTipsForConsensus[F[_]: Monad](
    blockStorage: BlockStorage[F],
    tipsCount: PosInt
  ): F[Boolean] =
    blockStorage.getTips(tipsCount).map(_.isDefined)

  private def atLeastOneTransaction[F[_]: Monad](
    transactionStorage: TransactionStorage[F]
  ): F[Boolean] =
    transactionStorage.countAllowedForConsensus.map(_ >= 1)

  def canStartOwnConsensus[F[_]: Async](
    consensusStorage: ConsensusStorage[F],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    blockStorage: BlockStorage[F],
    transactionStorage: TransactionStorage[F],
    peersCount: PosInt,
    tipsCount: PosInt
  ): F[Boolean] =
    for {

      noOwnRoundInProgress <- consensusStorage.ownConsensus.get.map(_.isEmpty)
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForBlockConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
      enoughTips <- enoughTipsForConsensus(blockStorage, tipsCount)
      enoughTxs <- atLeastOneTransaction(transactionStorage)

      res = noOwnRoundInProgress && stateReadyForConsensus && enoughPeers && enoughTips && enoughTxs
      _ <-
        Applicative[F].whenA(!res) {
          val reason = Seq(
            if (!noOwnRoundInProgress) "Own round in progress" else "",
            if (!stateReadyForConsensus) "State not ready for consensus" else "",
            if (!enoughPeers) "Not enough peers" else "",
            if (!enoughTips) "Not enough tips" else "",
            if (!enoughTxs) "No transactions" else ""
          ).filter(_.nonEmpty).mkString(", ")
          logger.debug(s"Cannot start own consensus: ${reason}; peersCount ${peersCount}")
        }
    } yield res

  def isPeerInputValid[F[_]: Async: SecurityProvider](
    input: Signed[PeerBlockConsensusInput]
  ): F[Boolean] =
    for {
      hasValidSignature <- input.hasValidSignature
      isSignedBy = input.isSignedExclusivelyBy(PeerId._Id.get(input.value.senderId))
      _ <- logger.info(s"isSignedBy ${isSignedBy}, input ${input}, hasValidSignature ${hasValidSignature}")
    } yield hasValidSignature && isSignedBy
}
