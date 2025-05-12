package org.reality.sdk.infrastructure.consensus

import cats.Order
import cats.data.{NonEmptyChain, NonEmptySet}
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.collection.immutable.SortedMap

import org.reality.ext.crypto._
import org.reality.schema.gossip.Ordinal
import org.reality.schema.peer.PeerId
import org.reality.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.reality.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger

import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact, Context] {
  private[consensus] trait ModifyStateFn[B]
      extends (Option[ConsensusState[Key, Artifact, Context]] => F[Option[(Option[ConsensusState[Key, Artifact, Context]], B)]])

  def getState(key: Key): F[Option[ConsensusState[Key, Artifact, Context]]]

  private[consensus] def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]]

  private[consensus] def containsTriggerEvent: F[Boolean]

  private[consensus] def addTriggerEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit]

  private[consensus] def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]]

  private[consensus] def getUpperBound: F[Bound]

  def getResources(key: Key): F[ConsensusResources[Artifact]]

  private[consensus] def addTrigger(trigger: ConsensusTrigger): F[Unit]

  private[consensus] def getTriggers(): F[Set[ConsensusTrigger]]

  private[consensus] def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]]

  private[consensus] def addFacility(peerId: PeerId, key: Key, facility: Facility): F[ConsensusResources[Artifact]]

  private[consensus] def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]]

  private[consensus] def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[ConsensusResources[Artifact]]

  private[consensus] def addWithdrawPeerDeclaration(peerId: PeerId, key: Key, kind: PeerDeclarationKind): F[ConsensusResources[Artifact]]

  private[consensus] def trySetInitialConsensusOutcome(data: ConsensusOutcome[Key, Artifact, Context]): F[Boolean]

  private[consensus] def clearAndGetLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]]

  def getLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]]

  def getLastState: F[Option[ConsensusState[Key, Artifact, Context]]]

  def getLastKey: F[Option[Key]]

  def getInProgressKeys(): F[Option[NonEmptyChain[Key]]]

  private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
    previousLastKey: Key,
    lastOutcome: ConsensusOutcome[Key, Artifact, Context]
  ): F[Boolean]

  private[consensus] def getOwnRegistration: F[Option[Key]]

  private[consensus] def setOwnRegistration(from: Key): F[Unit]

  def getCandidates(key: Key): F[Set[PeerId]]

  private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Boolean]

  private[consensus] def setFacilitators(startingAt: Key, facilitators: NonEmptySet[PeerId]): F[Unit]

  private[consensus] def isActiveFacilitator(key: Key): F[Boolean]

  private[consensus] def getFacilitators(at: Key): F[Option[NonEmptySet[PeerId]]]

}

object ConsensusStorage {

  def make[F[_]: Async, Event, Key: Order: Ordering, Artifact: Encoder, Context <: AnyRef](
    nodeId: PeerId
  ): F[ConsensusStorage[F, Event, Key, Artifact, Context]] =
    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      lastOutcomeR <- Ref.of(none[ConsensusOutcome[Key, Artifact, Context]])
      triggersR <- Ref.of(Set.empty[ConsensusTrigger])
      ownRegistrationR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, Key])
      eventsR <- MapRef.ofConcurrentHashMap[F, PeerId, PeerEvents[Event]]()
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Artifact, Context]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact]]()
      facilitatorsR <- Ref.of(SortedMap.empty[Key, NonEmptySet[PeerId]])
    } yield
      new ConsensusStorage[F, Event, Key, Artifact, Context] {

        def getState(key: Key): F[Option[ConsensusState[Key, Artifact, Context]]] =
          statesR(key).get

        def getResources(key: Key): F[ConsensusResources[Artifact]] =
          resourcesR(key).get.map(_.getOrElse(ConsensusResources.empty))

        private[consensus] def addTrigger(trigger: ConsensusTrigger) =
          triggersR.update(_ + trigger)

        private[consensus] def getTriggers() =
          triggersR.modify { current =>
            (Set.empty, current)
          }

        def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]] =
          stateUpdateSemaphore.permit.use { _ =>
            for {
              (maybeState, setter) <- statesR(key).access
              maybeResult <- modifyStateFn(maybeState)

              maybeB <- maybeResult.traverse {
                case (maybeState, b) =>
                  setter(maybeState)
                    .ifM(
                      b.pure[F],
                      new Throwable(
                        "Failed consensus state update, all consensus state updates should be sequenced with a semaphore"
                      ).raiseError[F, B]
                    )
              }
            } yield maybeB
          }

        def trySetInitialConsensusOutcome(initialOutcome: ConsensusOutcome[Key, Artifact, Context]): F[Boolean] =
          lastOutcomeR.modify {
            case s @ Some(_) => (s, false)
            case None        => (initialOutcome.some, true)
          }

        def clearAndGetLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]] =
          lastOutcomeR.getAndSet(none)

        def getLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]] =
          lastOutcomeR.get

        def getLastState
          : F[Option[ConsensusState[Key, Artifact, Context]]] = // TODO: not atomic, taking key and based on a key an actual state
          statesR.keys
            .map(_.maximumOption)
            .flatMap(_.flatTraverse(k => statesR(k).get))

        def getLastKey: F[Option[Key]] =
          lastOutcomeR.get.map(_.map(_.key))

        def getInProgressKeys(): F[Option[NonEmptyChain[Key]]] =
          statesR.keys.map(keys => NonEmptyChain.fromSeq(keys.sorted))

        private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
          previousLastKey: Key,
          newLastOutcome: ConsensusOutcome[Key, Artifact, Context]
        ): F[Boolean] =
          lastOutcomeR.modify {
            case Some(lastOutcome) if lastOutcome.key === previousLastKey =>
              (newLastOutcome.some, true)
            case other @ _ =>
              (other, false)
          }.flatTap { result =>
            cleanupStateAndResource(previousLastKey).whenA(result)
          }.flatTap(_ => cleanHistoricalValidators(previousLastKey))

        private def cleanupStateAndResource(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Artifact, Context]], ()).some.pure[F]
          }.void >> cleanResources(key)

        def containsTriggerEvent: F[Boolean] =
          eventsR.keys.flatMap { keys =>
            keys.existsM { peerId =>
              eventsR(peerId).get
                .map(_.flatMap(_.trigger).isDefined)
            }
          }

        def addTriggerEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit] =
          addEvents(peerId, List(peerEvent), updateTrigger = true)

        def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit] =
          addEvents(peerId, List(peerEvent), updateTrigger = false)

        def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit] =
          events.toList.traverse {
            case (peerId, peerEvents) =>
              addEvents(peerId, peerEvents, updateTrigger = false)
          }.void

        private def addEvents(peerId: PeerId, events: List[(Ordinal, Event)], updateTrigger: Boolean) =
          eventsR(peerId).update { maybePeerEvents =>
            maybePeerEvents
              .getOrElse(PeerEvents.empty[Event])
              .focus(_.events)
              .modify(events ++ _)
              .focus(_.trigger)
              .modify { maybeCurrentTrigger =>
                if (updateTrigger) {
                  val maybeNewTrigger = events.map(_._1).maximumOption

                  (maybeCurrentTrigger, maybeNewTrigger)
                    .mapN(Order[Ordinal].max)
                    .orElse(maybeCurrentTrigger)
                    .orElse(maybeNewTrigger)
                } else
                  maybeCurrentTrigger
              }
              .some
          }

        def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]] =
          upperBound.toList.traverse {
            case (peerId, peerBound) =>
              eventsR(peerId).modify { maybePeerEvents =>
                maybePeerEvents.traverse { peerEvents =>
                  val (eventsAboveBound, pulledEvents) = peerEvents.events.partition {
                    case (eventOrdinal, _) => eventOrdinal > peerBound
                  }
                  val updatedPeerEvents = peerEvents
                    .focus(_.events)
                    .replace(eventsAboveBound)
                    .focus(_.trigger)
                    .modify(_.filter(_ > peerBound))

                  (pulledEvents, updatedPeerEvents)
                }.swap
              }.map((peerId, _))
          }.map(_.toMap)

        def getUpperBound: F[Bound] =
          for {
            peerIds <- eventsR.keys
            bound <- peerIds.traverseFilter { peerId =>
              eventsR(peerId).get.map { maybePeerEvents =>
                maybePeerEvents.flatMap { peerEvents =>
                  peerEvents.events.map(_._1).maximumOption.map((peerId, _))
                }
              }
            }
          } yield bound.toMap

        def addFacility(peerId: PeerId, key: Key, facility: Facility): F[ConsensusResources[Artifact]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).modify(_.orElse(facility.some))
          }

        def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.proposal).modify(_.orElse(proposal.some))

          }

        def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[ConsensusResources[Artifact]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.signature).modify(_.orElse(signature.some))
          }

        def addWithdrawPeerDeclaration(peerId: PeerId, key: Key, kind: PeerDeclarationKind): F[ConsensusResources[Artifact]] =
          updateResources(key) { resources =>
            resources
              .focus(_.withdrawalsMap)
              .at(peerId)
              .modify { maybeKind =>
                maybeKind.orElse(kind.some)
              }
          }

        def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]] =
          artifact.hashF.flatMap { hash =>
            updateResources(key) { resources =>
              resources
                .focus(_.artifacts)
                .at(hash)
                .replace(artifact.some)
            }
          }

        private def updatePeerDeclaration(key: Key, peerId: PeerId)(f: PeerDeclarations => PeerDeclarations) =
          updateResources(key) { resources =>
            resources
              .focus(_.peerDeclarationsMap)
              .at(peerId)
              .modify { maybePeerDeclaration =>
                f(maybePeerDeclaration.getOrElse(PeerDeclarations.empty)).some
              }
          }

        private def updateResources(key: Key)(f: ConsensusResources[Artifact] => ConsensusResources[Artifact]) =
          resourcesR(key).updateAndGet { maybeResource =>
            f(maybeResource.getOrElse(ConsensusResources.empty)).some
          }.flatMap(_.liftTo[F](new RuntimeException("Should never happen")))

        private def cleanResources(key: Key): F[Unit] =
          resourcesR(key).set(none)

        def getOwnRegistration: F[Option[Key]] = ownRegistrationR.get

        def setOwnRegistration(key: Key): F[Unit] = ownRegistrationR.set(key.some)

        def getCandidates(key: Key): F[Set[PeerId]] =
          peerRegistrationsR.get.map { peerRegistrations =>
            peerRegistrations.toList.mapFilter {
              case (peerId, at) if key === at => peerId.some
              case _                          => none[PeerId]
            }.toSet
          }

        def registerPeer(peerId: PeerId, newKey: Key): F[Boolean] =
          peerRegistrationsR.modify { peerRegistrations =>
            val result = peerRegistrations
              .focus()
              .at(peerId)
              .modify { maybeKey =>
                maybeKey
                  .filter(_ > newKey)
                  .getOrElse(newKey)
                  .some
              }
            (result, result.get(peerId).exists(_ === newKey))
          }

        private[consensus] def isActiveFacilitator(key: Key): F[Boolean] =
          facilitatorsR.get.map { facilitatorsPerKey =>
            facilitatorsPerKey
              .get(key)
              .orElse(facilitatorsPerKey.maxBefore(key).map { case (_, f) => f })
              .exists(_.contains(nodeId))
          }

        private[consensus] def setFacilitators(startingAt: Key, facilitators: NonEmptySet[PeerId]): F[Unit] =
          facilitatorsR
            .update(_ + (startingAt -> facilitators))

        private[consensus] def getFacilitators(at: Key): F[Option[NonEmptySet[PeerId]]] =
          facilitatorsR.get.map(_.get(at))

        private def cleanHistoricalValidators(key: Key): F[Unit] =
          facilitatorsR.update { current =>
            val maybeCleanBelow = current.maxBefore(key).map { case (k, _) => k }

            current.filterNot { case (k, _) => maybeCleanBelow.exists(_ > k) }
          }
      }
}
