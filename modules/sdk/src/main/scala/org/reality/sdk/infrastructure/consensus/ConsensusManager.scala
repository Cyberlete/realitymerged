package org.reality.sdk.infrastructure.consensus

import cats._
import cats.data.{Ior, NonEmptySet}
import cats.effect._
import cats.effect.std.{Queue, Random, Supervisor}
import cats.kernel.{Next, PartialPrevious}
import cats.syntax.all._

import scala.concurrent.duration._

import org.reality.ext.cats.syntax.keyIndex.syntaxKeyIndex
import org.reality.ext.cats.syntax.next._
import org.reality.ext.cats.syntax.partialPrevious._
import org.reality.schema.KeyIndex
import org.reality.schema.node.NodeState
import org.reality.schema.node.NodeState._
import org.reality.schema.peer.Peer.toP2PContext
import org.reality.schema.peer.{Peer, PeerId}
import org.reality.sdk.config.types.ConsensusConfig
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.infrastructure.consensus.message.GetConsensusOutcomeRequest
import org.reality.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.{Pipe, Pull, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

trait ConsensusManager[F[_], Key, Artifact, Context] {
  def startObserving: F[Unit]
  def startFacilitatingAfter(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context
  ): F[Unit]

  def withdrawFromConsensus: F[Unit]
  private[consensus] def facilitateOnEvent: F[Unit]
  private[consensus] def followConsensus: F[Unit]
  private[consensus] def requestStateUpdate(): F[Unit]
  private[consensus] val updateRuntime: Stream[F, Unit]
  private[consensus] val consensusTriggerRuntime: Stream[F, Unit]
}

object ConsensusManager {

  def make[F[
    _
  ]: Async: Random: Metrics: SecurityProvider, Event, Key: Show: Order: Next: KeyIndex: PartialPrevious, Artifact: Eq, Context: Eq](
    config: ConsensusConfig,
    consensusFunctions: ConsensusFunctions[F, Event, Key, Artifact, Context],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context],
    consensusStateRemover: ConsensusStateRemover[F, Key, Artifact],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Artifact, Context],
    selfId: PeerId
  )(implicit S: Supervisor[F]): F[ConsensusManager[F, Key, Artifact, Context]] =
    (Queue.dropping[F, Unit](1), Queue.unbounded[F, ConsensusTrigger]).flatMapN {
      case (updateRequestQueue, triggerRequestQueue) =>
        make[F, Event, Key, Artifact, Context](
          config,
          updateRequestQueue,
          triggerRequestQueue,
          consensusFunctions,
          consensusStorage,
          consensusStateCreator,
          consensusStateUpdater,
          consensusStateRemover,
          nodeStorage,
          clusterStorage,
          consensusClient,
          selfId
        )
    }

  private def make[F[
    _
  ]: Async: Random: Metrics: SecurityProvider, Event, Key: Show: Order: Next: KeyIndex: PartialPrevious, Artifact: Eq, Context: Eq](
    config: ConsensusConfig,
    updateRequestQueue: Queue[F, Unit],
    triggerRequestQueue: Queue[F, ConsensusTrigger],
    consensusFunctions: ConsensusFunctions[F, Event, Key, Artifact, Context],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context],
    consensusStateRemover: ConsensusStateRemover[F, Key, Artifact],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Artifact, Context],
    selfId: PeerId
  )(implicit S: Supervisor[F]): F[ConsensusManager[F, Key, Artifact, Context]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    val observationRetryPolicy = limitRetries[F](5).join(constantDelay(30.seconds))

    def collectRegistration(peer: Peer): F[Unit] =
      for {
        registrationResponse <- consensusClient.getRegistration.run(peer)
        maybeResult <- registrationResponse.maybeKey.traverse(consensusStorage.registerPeer(peer.id, _))
        _ <- (registrationResponse.maybeKey, maybeResult).traverseN {
          case (key, result) =>
            if (result)
              logger.info(s"Peer ${peer.id.show} registered at ${key.show}")
            else
              logger.warn(s"Peer ${peer.id.show} cannot be registered at ${key.show}")
        }
      } yield ()

    val manager = new ConsensusManager[F, Key, Artifact, Context] {

      def startObserving: F[Unit] =
        S.supervise {

          def getObservedOutcome: F[ConsensusOutcome[Key, Artifact, Context]] = for {
            readyPeers <- clusterStorage.getResponsivePeers
              .map(_.filter(_.state === Ready))
            selectedPeer <- Random[F].elementOf(readyPeers)
            observedOutcome <- observePeer(selectedPeer)
          } yield observedOutcome

          getObservedOutcome
            .retryingOnAllErrors(
              observationRetryPolicy,
              (err, retryDetails) =>
                logger.error(err)(s"Error when trying to observe consensus outcome {attempt=${retryDetails.retriesSoFar}}") >>
                  nodeStorage.tryModifyStateGetResult(Observing, WaitingForObserving).void
            )
            .flatMap { observedOutcome =>
              consensusStorage
                .trySetInitialConsensusOutcome(observedOutcome)
                .ifM(
                  nodeStorage.tryModifyState(Observing, WaitingForReady) >>
                    consensusFunctions.extractNextValidators(observedOutcome.status.context).flatMap {
                      case (at, facilitators) => consensusStorage.setFacilitators(at, facilitators)
                    } >>
                    internalFollowConsensus,
                  new Throwable("Error initializing consensus storage").raiseError[F, Unit]
                )
            }
            .handleErrorWith { err =>
              logger.error(err)("Error when trying to observe consensus outcome, giving up.")
            }
        }.void

      private def observePeer(peer: Peer): F[ConsensusOutcome[Key, Artifact, Context]] = {

        def getSpecificOutcome(key: Key): F[Option[ConsensusOutcome[Key, Artifact, Context]]] = consensusClient
          .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
          .map(_.filter(_.key === key))
          .run(peer)

        for {
          maybeLatestOutcome <- consensusClient.getLatestConsensusOutcome.run(peer)
          latestOutcome <- maybeLatestOutcome.liftTo[F](new Throwable(s"Peer ${peer.id.show} doesn't have last outcome"))
          observationKey = latestOutcome.key.nextN(config.observation.offset)
          _ <- logger.info(s"Awaiting for consensus outcome {key=${observationKey.show}, peerId=${peer.id.show}}")
          _ <- consensusStorage.setOwnRegistration(observationKey.nextN(2L))
          _ <- nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing)
          observationOutcome <- Temporal[F].timeout(
            (Temporal[F].sleep(config.observation.interval) >> getSpecificOutcome(observationKey)).untilDefinedM,
            config.observation.timeout
          )
        } yield observationOutcome
      }

      def facilitateOnEvent: F[Unit] =
        triggerRequestQueue.offer(EventTrigger)

      def followConsensus: F[Unit] =
        S.supervise {
          internalFollowConsensus
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfter(
        lastKey: Key,
        lastArtifact: Signed[Artifact],
        lastContext: Context
      ): F[Unit] =
        Clock[F].monotonic.flatMap { startedAt =>
          val initialOutcome = ConsensusOutcome(
            lastKey,
            List(selfId),
            startedAt,
            Finished(
              lastArtifact,
              lastContext,
              EventTrigger,
              Hash.empty,
              Map()
            )
          )

          consensusStorage
            .trySetInitialConsensusOutcome(initialOutcome)
            .ifM(
              consensusStorage.setOwnRegistration(lastKey.next) >>
                consensusStorage.setFacilitators(lastKey, NonEmptySet.one(selfId)) >>
                consensusFunctions.extractNextValidators(lastContext).flatMap {
                  case (at, facilitators) => consensusStorage.setFacilitators(at, facilitators)
                } >>
                scheduleFacility,
              new Throwable("Error initializing consensus storage").raiseError[F, Unit]
            )
        }

      private def scheduleFacility: F[Unit] =
        Clock[F].monotonic.map(_ + config.timeTriggerInterval).flatMap { nextTimeValue =>
          logger.warn(s"Scheduling facility with time $nextTimeValue") >>
            S.supervise {
              Temporal[F].sleep(config.timeTriggerInterval) >>
                triggerRequestQueue.offer(TimeTrigger)
            }.void
        }

      def withdrawFromConsensus: F[Unit] =
        for {
          maybeLastOutcome <- consensusStorage.clearAndGetLastConsensusOutcome
          _ <- maybeLastOutcome.traverse { lastOutcome =>
            consensusStateRemover.withdrawFromConsensus(lastOutcome.key.next)
          }
        } yield ()

      def requestStateUpdate(): F[Unit] = updateRequestQueue.offer(())

      private def fetchLastStateForRoundStart(): F[Option[ConsensusState[Key, Artifact, Context]]] =
        (consensusStorage.getLastConsensusOutcome, consensusStorage.getLastState, Clock[F].monotonic).mapN {
          case (maybeOutcome, maybeState, time) =>
            maybeState.orElse(maybeOutcome.map(_.toState(selfId))).filter { state =>
              val isFinished = state.status.isInstanceOf[Finished[_, _]]
              val patienceExceeded = state.createdAt.plus(config.declarationTimeout) <= time

              isFinished || patienceExceeded
            } // TODO: check if there is no chance for consensus waterfall
        }

      private def internalFollowConsensus: F[Unit] =
        fetchLastStateForRoundStart().flatMap { maybeLastState =>
          maybeLastState.traverse { lastState =>
            val nextKey = lastState.key.next

            consensusStorage
              .isActiveFacilitator(nextKey)
              .ifM(
                Applicative[F].unit,
                consensusStorage
                  .getResources(nextKey)
                  .flatMap { resources =>
                    logger.debug(s"Trying to follow consensus {key=${nextKey.show}}}") >>
                      consensusStateCreator.tryFollowConsensus(nextKey, lastState, resources).flatMap {
                        case Some(_) =>
                          requestStateUpdate()
                        case None => Applicative[F].unit
                      }
                  }
              )
          }.void
        }

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Boolean] =
        fetchLastStateForRoundStart().flatMap {
          case Some(lastState) =>
            val nextKey = lastState.key.next

            consensusStorage
              .isActiveFacilitator(nextKey)
              .ifM(
                consensusStorage
                  .getResources(nextKey)
                  .flatMap { resources =>
                    logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                      consensusStateCreator.tryFacilitateConsensus(nextKey, lastState, trigger, resources).flatMap {
                        case Some(_) => requestStateUpdate() >> Applicative[F].pure(true)
                        case None    => Applicative[F].pure(false)
                      }
                  },
                Applicative[F].pure(false)
              )
          case None => Applicative[F].pure(false)
        }

      private def tryAdvance(): F[Unit] =
        (consensusStorage.getLastConsensusOutcome, consensusStorage.getInProgressKeys()).flatMapN {
          case (o, k) =>
            (o, k).traverseN {
              case (outcome, keys) =>
                val maxKeyIdx = keys.maximum.index
                val initialState = outcome.toState(selfId)
                (initialState, keys.toList).tailRecM[F, Unit] {
                  case (_, Nil) => Async[F].unit.map(_.asRight)
                  case (prevRoundState, k :: keys) =>
                    consensusStorage
                      .getResources(k)
                      .flatMap { res =>
                        val delay = NonNegLong.from(maxKeyIdx - k.index).getOrElse(NonNegLong.MinValue)

                        internalCheckForStateUpdate(prevRoundState, k, res, delay)
                      }
                      .map {
                        case Some(state) => (state, keys).asLeft
                        case None        => ().asRight
                      }
                }
            }.void
        }

      private def internalCheckForStateUpdate(
        previousRoundState: ConsensusState[Key, Artifact, Context],
        key: Key,
        resources: ConsensusResources[Artifact],
        delay: NonNegLong
      ): F[Option[ConsensusState[Key, Artifact, Context]]] =
        consensusStateUpdater.tryUpdateConsensus(key, resources, previousRoundState, delay).flatMap {
          case Some((_, newState)) =>
            newState.status match {
              case finished @ Finished(_, _, majorityTrigger, _, entMap) =>
                Clock[F].monotonic.flatMap { finishedAt =>
                  Metrics[F].recordTime("net_consensus_duration", finishedAt - newState.createdAt)
                } >> {
                  newState.key.partialPrevious.traverse { previousKey =>
                    consensusFunctions.extractNextValidators(finished.context).flatMap {
                      // TODO: only update if it's a rotation moment?
                      case (at, facilitators) => consensusStorage.setFacilitators(at, facilitators)
                    } >>
                      consensusStorage
                        .tryUpdateLastConsensusOutcomeWithCleanup(
                          previousKey,
                          ConsensusOutcome(
                            newState.key,
                            newState.facilitators,
                            newState.createdAt,
                            finished
                          )
                        )
                  }
                    .map(_.exists(identity))
                    .ifM(
                      afterConsensusFinish(majorityTrigger),
                      logger.info("Skip triggering another consensus")
                    )
                } >>
                  consensusStateUpdater.getTrustStorage.cleanCache().whenA(newState.key.index % SelectActivePeers.selectionInterval == 0) >>
                  consensusStateUpdater.getTrustStorage.updateInfluenceCache(entMap, newState.key.index) >>
                  nodeStorage.tryModifyStateGetResult(WaitingForReady, Ready).void.as(newState.some)
              case _ =>
                internalCheckForStateUpdate(previousRoundState, key, resources, delay)
            }
          case None => consensusStorage.getState(key)
        }

      private def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
        majorityTrigger match {
          case EventTrigger => afterEventTrigger
          case TimeTrigger  => afterTimeTrigger
        }

      private def afterEventTrigger: F[Unit] =
        consensusStorage.containsTriggerEvent
          .ifM(
            triggerRequestQueue.offer(EventTrigger),
            Applicative[F].unit
          )

      private def afterTimeTrigger: F[Unit] =
        scheduleFacility >> consensusStorage.containsTriggerEvent
          .ifM(triggerRequestQueue.offer(EventTrigger), Applicative[F].unit)

      private[consensus] val updateRuntime: Stream[F, Unit] =
        Stream
          .fromQueueUnterminated(updateRequestQueue)
          .evalMap(_ => tryAdvance())

      private[consensus] val consensusTriggerRuntime: Stream[F, Unit] = {
        def emitEventTriggerAfter60sOfInactivity: Pipe[F, ConsensusTrigger, ConsensusTrigger] =
          _.pull.timed { timedPull =>
            def go(timedPull: Pull.Timed[F, ConsensusTrigger]): Pull[F, ConsensusTrigger, Unit] =
              timedPull.timeout(60.seconds) >>
                timedPull.uncons.flatMap {
                  case Some((Right(elems), next)) => Pull.output(elems) >> go(next)
                  case Some((Left(_), next)) =>
                    Pull.eval(logger.warn("Detected 60 seconds of inactivity generating TriggerEvent")) >>
                      Pull.output1(EventTrigger) >>
                      go(next)
                  case None => Pull.done
                }

            go(timedPull)
          }.stream

        def tryStartConsensus() =
          consensusStorage.getTriggers().flatMap { triggers =>
            val hasStarted =
              if (triggers.contains(TimeTrigger))
                internalFacilitateWith(TimeTrigger.some)
              else if (triggers.contains(EventTrigger))
                internalFacilitateWith(EventTrigger.some)
              else Applicative[F].pure(true)

            hasStarted
              .ifM(
                Applicative[F].unit,
                triggers.toList.traverse(consensusStorage.addTrigger).void
              )
          }

        Stream
          .fromQueueUnterminated(triggerRequestQueue)
          .through(emitEventTriggerAfter60sOfInactivity)
          .evalMap { trigger =>
            consensusStorage.addTrigger(trigger) >>
              tryStartConsensus()
          }
      }
    }

    S.supervise(
      manager.updateRuntime.compile.drain
    ) >>
      S.supervise(
        manager.consensusTriggerRuntime.compile.drain
      ) >>
      S.supervise(
        nodeStorage.nodeStates
          .filter(_ === NodeState.Leaving)
          .evalTap { _ =>
            manager.withdrawFromConsensus
          }
          .compile
          .drain
      ) >>
      S.supervise(
        clusterStorage.peerChanges.mapFilter {
          case Ior.Both(_, peer) if peer.state === NodeState.Observing =>
            peer.some
          case Ior.Right(peer) if peer.state === NodeState.Observing =>
            peer.some
          case _ =>
            none[Peer]
        }
          .filter(_.isResponsive)
          .parEvalMapUnbounded { peer =>
            collectRegistration(peer)
              .handleErrorWith(err => logger.error(err)(s"Error exchanging registration with peer ${peer.show}"))
          }
          .compile
          .drain
      ).as(manager)
  }
}
