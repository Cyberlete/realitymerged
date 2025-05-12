package org.reality.sdk.infrastructure.consensus

import java.security.KeyPair

import cats._
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap
import scala.reflect.runtime.universe.TypeTag

import org.reality.ext.cats.syntax.keyIndex.syntaxKeyIndex
import org.reality.ext.cats.syntax.next._
import org.reality.ext.crypto._
import org.reality.schema.KeyIndex
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.consensus.declaration.{Facility, kind}
import org.reality.sdk.infrastructure.consensus.message.ConsensusPeerDeclaration
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.reality.security.SecurityProvider

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateCreator[F[_], Key, Artifact, Context] {

  type StateCreateResult = Option[ConsensusState[Key, Artifact, Context]]

  /** Tries to facilitate consensus. Returns `Some(state)` if state with `key` didn't exist, otherwise returns `None`
    */
  def tryFacilitateConsensus(
    key: Key,
    previousState: ConsensusState[Key, Artifact, Context],
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact]
  ): F[StateCreateResult]

  def tryFollowConsensus(
    key: Key,
    previousState: ConsensusState[Key, Artifact, Context],
    resources: ConsensusResources[Artifact]
  ): F[StateCreateResult]

}

object ConsensusStateCreator {
  def make[F[
    _
  ]: Async: SecurityProvider, Event, Key: Show: Next: TypeTag: Encoder: KeyIndex, Artifact <: AnyRef, Context <: AnyRef](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    trustStorage: TrustStorage[F],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]]
  ): ConsensusStateCreator[F, Key, Artifact, Context] = new ConsensusStateCreator[F, Key, Artifact, Context] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateCreator.getClass)

    def tryFacilitateConsensus(
      key: Key,
      previousState: ConsensusState[Key, Artifact, Context],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[StateCreateResult] =
      tryCreateNewConsensus(key, facilitateConsensus(key, previousState, maybeTrigger, resources))

    def tryFollowConsensus(
      key: Key,
      previousState: ConsensusState[Key, Artifact, Context],
      resources: ConsensusResources[Artifact]
    ): F[StateCreateResult] =
      tryCreateNewConsensus(key, followConsensus(key, previousState, resources))

    private def tryCreateNewConsensus(
      key: Key,
      fn: F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(fn))
        .flatMap(evalEffect)
        .flatTap(logIfCreatedState)

    import consensusStorage.ModifyStateFn

    private def toCreateStateFn(
      fn: F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): ModifyStateFn[(StateCreateResult, F[Unit])] = {
      case None =>
        fn.map {
          case (state, effect) => (state.some, (state.some, effect)).some
        }
      case Some(_) => none.pure[F]
    }

    private def evalEffect(maybeResultAndEffect: Option[(StateCreateResult, F[Unit])]): F[StateCreateResult] =
      maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

    private def logIfCreatedState(createResult: StateCreateResult): F[Unit] =
      createResult.traverse { state =>
        logger.info(s"State created ${state.show}")
      }.void

    private def facilitateConsensus(
      key: Key,
      previousState: ConsensusState[Key, Artifact, Context],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[(ConsensusState[Key, Artifact, Context], F[Unit])] =
      for {

        newCandidates <- consensusStorage.getCandidates(key.next)
        _ <- logger.info(s"New candidates ${newCandidates.map(_.value.shortValue)}")

        facilitators <-
          if (key.index % SelectActivePeers.selectionInterval.value == 0)
            consensusStorage
              .getFacilitators(key)
              .map(
                _.map(_.toNonEmptyList.toList).get
              ) // TODO: shouldn't be possible to not have the next facilitators at this moment but how to handle it
          else previousState.facilitators.pure[F]

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(kind.Facility)
        }

        facilitatorsHash <- remained.hashF

        signedTrustMap <- trustStorage.getInfluenceCache.flatMap { influenceCache =>
          val trustMap = SortedMap.from(influenceCache.view.mapValues(_.predictedTrust.getOrElse(1.0)))
          trustMap.sign(keyPair)
        }

        time <- Clock[F].monotonic
        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(bound, newCandidates, maybeTrigger, facilitatorsHash, signedTrustMap.proofs.head.signature)
            )
          )
        }
        state = ConsensusState(
          selfId,
          key,
          remained,
          CollectingFacilities[Artifact, Context](
            facilitatorsHash,
            signedTrustMap
          ),
          time,
          withdrawnFacilitators = withdrawn.toSet
        )
      } yield (state, effect)

    private def followConsensus(
      key: Key,
      previousState: ConsensusState[Key, Artifact, Context],
      resources: ConsensusResources[Artifact]
    ): F[(ConsensusState[Key, Artifact, Context], F[Unit])] =
      for {
        time <- Clock[F].monotonic
        emptyInfluenceMap <- SortedMap.empty[PeerId, Double].sign(keyPair)
        facilitators <-
          if (key.index % SelectActivePeers.selectionInterval.value == 0)
            consensusStorage
              .getFacilitators(key)
              .map(
                _.map(_.toNonEmptyList.toList).get
              ) // TODO: shouldn't be possible to not have the next facilitators at this moment but how to handle it
          else previousState.facilitators.pure[F]
        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(kind.Facility)
        }
        facilitatorsHash <- remained.hashF
        state = ConsensusState(
          selfId,
          key,
          remained,
          CollectingFacilities[Artifact, Context](facilitatorsHash, emptyInfluenceMap),
          time,
          withdrawnFacilitators = withdrawn.toSet
        )
      } yield (state, Applicative[F].unit)

  }
}
