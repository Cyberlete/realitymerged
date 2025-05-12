package org.reality.sdk.infrastructure.consensus

import java.security.KeyPair

import cats._
import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.kernel.Next
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.reflect.runtime.universe.TypeTag

import org.reality.dag.domain.block.NETBlock
import org.reality.ext.cats.syntax.keyIndex.syntaxKeyIndex
import org.reality.ext.crypto._
import org.reality.schema.KeyIndex
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.consensus.declaration._
import org.reality.sdk.infrastructure.consensus.message._
import org.reality.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.trust.TrustModel.entropyRate
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.security.signature.Signed.SignedOps
import org.reality.security.signature.signature.{Signature, SignatureProof, verifySignatureProof}
import org.reality.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact, Context] {

  type StateUpdateResult = Option[(ConsensusState[Key, Artifact, Context], ConsensusState[Key, Artifact, Context])]
  def getTrustStorage: TrustStorage[F]

  /** Tries to conditionally update a consensus based on information collected in `resources`, this includes:
    *   - updating facilitators,
    *   - advancing consensus status.
    *
    * Returns `Some((oldState, newState))` when the consensus with `key` exists and update was successful, otherwise `None`
    */
  def tryUpdateConsensus(
    key: Key,
    resources: ConsensusResources[Artifact],
    previousRoundState: ConsensusState[Key, Artifact, Context],
    delay: NonNegLong
  ): F[StateUpdateResult]
}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async: SecurityProvider: Metrics, Event, Key: Show: Order: Next: KeyIndex: TypeTag: Encoder, Artifact <: AnyRef: Eq: TypeTag: Encoder, Context <: AnyRef: Eq: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    gossip: Gossip[F],
    keyPair: KeyPair,
    selfId: PeerId,
    trustStorage: TrustStorage[F],
    blockExtractor: Artifact => SortedSet[NETBlock]
  ): ConsensusStateUpdater[F, Key, Artifact, Context] = new ConsensusStateUpdater[F, Key, Artifact, Context] {

    def getTrustStorage = trustStorage
    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    def tryUpdateConsensus(
      key: Key,
      resources: ConsensusResources[Artifact],
      previousRoundState: ConsensusState[Key, Artifact, Context],
      delay: NonNegLong
    ): F[StateUpdateResult] =
      tryUpdateExistingConsensus(key, updateConsensus(resources, previousRoundState, delay))

    import consensusStorage.ModifyStateFn

    private def tryUpdateExistingConsensus(
      key: Key,
      fn: ConsensusState[Key, Artifact, Context] => F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): F[StateUpdateResult] =
      consensusStorage
        .condModifyState(key)(toUpdateStateFn(fn))
        .flatMap(evalEffect)
        .flatTap(logIfUpdatedState)

    private def toUpdateStateFn(
      fn: ConsensusState[Key, Artifact, Context] => F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): ModifyStateFn[(StateUpdateResult, F[Unit])] = { maybeState =>
      maybeState.flatTraverse { oldState =>
        fn(oldState).map {
          case (newState, effect) =>
            Option.when(newState =!= oldState)((newState.some, ((oldState, newState).some, effect)))
        }
      }
    }

    private def evalEffect(maybeResultAndEffect: Option[(StateUpdateResult, F[Unit])]): F[StateUpdateResult] =
      maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

    private def logIfUpdatedState(updateResult: StateUpdateResult): F[Unit] =
      updateResult.traverse {
        case (_, newState) =>
          logger.info(s"State updated ${newState.show}")
      }.void

    private def updateConsensus(
      resources: ConsensusResources[Artifact],
      previousRoundState: ConsensusState[Key, Artifact, Context],
      delay: NonNegLong
    )(
      state: ConsensusState[Key, Artifact, Context]
    ): F[(ConsensusState[Key, Artifact, Context], F[Unit])] = {
      val stateAndEffect = for {
        _ <- updateWithdrawingFacilitators(resources) // only the withdrawing peers are being removed here
        _ <- removeTransitively(previousRoundState)
        effect <- advanceStatus(resources, previousRoundState, delay)
      } yield effect

      stateAndEffect
        .run(state)
    }

    private def updateWithdrawingFacilitators(
      resources: ConsensusResources[Artifact]
    ): StateT[F, ConsensusState[Key, Artifact, Context], Unit] =
      StateT.modify { state =>
        if (resources.withdrawalsMap.isEmpty)
          state
        else
          state.maybeCollectingKind.map { collectingKind =>
            val (withdrawn, remained) = state.facilitators.partition { peerId =>
              resources.withdrawalsMap.get(peerId).contains(collectingKind)
            }
            state.copy(
              facilitators = remained,
              withdrawnFacilitators = state.withdrawnFacilitators.union(withdrawn.toSet)
            )
          }.getOrElse(state)
      }

    private def removeTransitively(
      previousRoundState: ConsensusState[Key, Artifact, Context]
    ): StateT[F, ConsensusState[Key, Artifact, Context], Unit] =
      StateT.modify { state =>
        val (removed, remained) = state.facilitators.partition(previousRoundState.removedFacilitators.contains)

        state.copy(
          facilitators = remained,
          removedFacilitators = state.removedFacilitators.union(removed.toSet)
        )
      }

    private def advanceStatus(
      resources: ConsensusResources[Artifact],
      previousRoundState: ConsensusState[Key, Artifact, Context],
      distance: NonNegLong
    ): StateT[F, ConsensusState[Key, Artifact, Context], F[Unit]] =
      StateT { state =>
        {
          state.status match {
            case CollectingFacilities(ownFacilitatorsHash, signedTrustMap) =>
              val maybeLastArtifactAndContext = extractArtifactAndContext(previousRoundState)

              val maybeFacilitiesAndAbsent = extractDeclarations(state, resources, (_, pd) => pd.facility, distance >= Patience.facility)

              (maybeFacilitiesAndAbsent, maybeLastArtifactAndContext)
                .mapN((_, _))
                .traverseTap {
                  case ((facilities, _), (_, _)) =>
                    warnIfForking(ownFacilitatorsHash)(facilities)
                }
                .flatMap { inputs =>
                  {
                    for {
                      ((facilities, absent), (lastArtifact, lastContext)) <- inputs
                      (bound, candidates, triggers) = facilities.foldMap(f => (f.upperBound, f.newCandidates, f.trigger.toList))
                      majorityTrigger <- pickMajority(triggers)
                    } yield (bound, candidates, majorityTrigger, absent, lastArtifact, lastContext)
                  }.traverse {
                    case (bound, newCandidates, majorityTrigger, absent, lastArtifact, lastContext) =>
                      val (toRemove, updatedFacilitators) = state.facilitators.partition(absent.contains)

                      updatedFacilitators.hashF.flatMap { facilitatorsHash =>
                        val facilitatorAction: F[(Option[ProposalInfo[Artifact, Context]], F[Unit])] =
                          for {
                            peerEvents <- consensusStorage.pullEvents(bound)
                            events = peerEvents.toList.flatMap(_._2).map(_._2).toSet
                            (
                              artifact,
                              context,
                              returnedEvents,
                              blocks
                            ) <-
                              consensusFns
                                .createProposalArtifact(
                                  state.key,
                                  lastArtifact,
                                  lastContext,
                                  majorityTrigger,
                                  events,
                                  SortedMap(selfId -> signedTrustMap),
                                  newCandidates,
                                  SortedSet.from(updatedFacilitators),
                                  SortedSet.from(toRemove),
                                  None
                                )
                            returnedPeerEvents = peerEvents.map {
                              case (peerId, events) =>
                                (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
                            }.filter { case (_, events) => events.nonEmpty }
                            _ <- consensusStorage.addEvents(returnedPeerEvents)
                            hash <- artifact.hashF
                            blocksSigned <- blocks.sign(keyPair)
                            maybeProposalInfo = ProposalInfo[Artifact, Context](artifact, context, hash, Map.empty).some
                            effect =
                              gossip.spread(
                                ConsensusPeerDeclaration(
                                  state.key,
                                  Proposal(
                                    hash,
                                    facilitatorsHash,
                                    blocksSigned,
                                    selfId,
                                    signedTrustMap = signedTrustMap,
                                    state.key.index
                                  )
                                )
                              ) *>
                                gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                          } yield (maybeProposalInfo, effect)

                        val followerAction: F[(Option[ProposalInfo[Artifact, Context]], F[Unit])] =
                          (none[ProposalInfo[Artifact, Context]], Applicative[F].unit).pure[F]

                        isFacilitator(updatedFacilitators.toSet)
                          .pure[F]
                          .ifM(ifTrue = facilitatorAction, ifFalse = followerAction)
                          .map {
                            case (maybeProposalInfo, effect) =>
                              val newState =
                                state.copy(
                                  status = CollectingProposals[Artifact, Context](
                                    majorityTrigger,
                                    maybeProposalInfo,
                                    newCandidates,
                                    facilitators = updatedFacilitators.toSet,
                                    removed = toRemove.toSet, // TODO: shouldn't removed also contain nodes that left gracefully?
                                    facilitatorsHash
                                  ),
                                  facilitators = updatedFacilitators,
                                  removedFacilitators = state.removedFacilitators ++ toRemove.toSet
                                )

                              (newState, effect)
                          }
                      }
                  }
                }
            // Will follow work exactly the same as a normal facilitator given it's not initiating it's own rounds but
            // must rely on signals from the actual facilitators? Check, I suspect there may be a problem where a follower
            // might track the fastest nodes rather than the majority...

            case CollectingProposals(majorityTrigger, maybeProposalInfo, candidates, propFacilitators, propRemoved, ownFacilitatorsHash) =>
              val maybeLastArtifactAndContext = extractArtifactAndContext(previousRoundState)

              // TODO: maybe we could have these datatypes (facility, proposal,...) such that the following always contains the previous, e.g. proposal, contain facility, signature contains proposal
              val maybeProposalsWithAbsentAndInvalidated =
                extractDeclarations(
                  state,
                  resources,
                  (_, pd) => (pd.facility, pd.proposal, pd.proposal.flatMap(p => resources.artifacts.get(p.hash))).mapN((_, _, _)),
                  distance >= Patience.proposal
                ).traverse {
                  case (facilitiesAndProposals, absent) =>
                    facilitiesAndProposals.traverse {
                      case (f, p, a) =>
                        val sentDeclaredTrustMap: F[Boolean] =
                          p.signedTrustMap.hasValidSignature.map { isValid =>
                            isValid && p.signedTrustMap.proofs.forall(_.signature == f.trustMapSignature)
                          }

                        val artifactContainsDeclaredBlocksAndIsSigned: F[Boolean] =
                          blockExtractor(a).toSeq.traverse(_.hashF).map(hashes => SortedSet.from(hashes)).flatMap { blockHashes =>
                            val isSignedByPeer =
                              p.blocks.isSignedBy(p.peerId.toId) // TODO: probably we can throw it out and not have to additionally validate
                            val isSignedCorrectly = p.blocks.hasValidSignature
                            val hasDeclaredBlocks = blockHashes === p.blocks

                            isSignedCorrectly.map(_ & isSignedByPeer & hasDeclaredBlocks)
                          }

                        val isValid = List(sentDeclaredTrustMap, artifactContainsDeclaredBlocksAndIsSigned).forallM(identity)

                        isValid.map((_, (p, a)))
                    }.map { proposalWithValidation =>
                      val (invalidatedPeers, proposals) = proposalWithValidation.partitionMap {
                        case (hasSent, (p, a)) => Either.cond(hasSent, (p, a), p.peerId)
                      }

                      (proposals, absent ++ invalidatedPeers.toSet)
                    }
                }

              (maybeProposalsWithAbsentAndInvalidated, maybeLastArtifactAndContext.pure[F]).mapN {
                case (a, b) => (a, b).mapN((_, _))
              }.flatMap {
                _.traverseTap {
                  case ((proposalsWithArtifacts, _), _) =>
                    warnIfForking(ownFacilitatorsHash)(proposalsWithArtifacts.map { case (p, _) => p })
                }.flatMap {
                  _.flatTraverse {
                    case ((proposalsWithArtifacts, absentAndInvalidated), (lastArtifact, lastContext)) =>
                      val (toRemove, updatedFacilitators) = state.facilitators.partition(absentAndInvalidated.contains)
                      pickValidatedMajority(
                        maybeProposalInfo,
                        lastArtifact,
                        lastContext,
                        majorityTrigger,
                        candidates,
                        SortedSet.from(propFacilitators),
                        SortedSet.from(propRemoved)
                      )(proposalsWithArtifacts).flatMap { maybeMajorityInfo: Option[ProposalInfo[Artifact, Context]] =>
                        updatedFacilitators.hashF.flatMap { facilitatorsHash =>
                          maybeMajorityInfo.traverse { majorityArtifactInfo: ProposalInfo[Artifact, Context] =>
                            val influenceMaps = SortedMap.from(proposalsWithArtifacts.map { case (p, _) => p.peerId -> p.signedTrustMap })
                            val proposalsBlocks = SortedMap.from(proposalsWithArtifacts.map { case (p, _) => p.peerId -> p.blocks })

                            consensusFns
                              .recalculateArtifactWithNewMetadata(
                                state.key,
                                lastArtifact,
                                lastContext,
                                majorityTrigger,
                                propFacilitators,
                                influenceMaps,
                                proposalsBlocks
                              )(majorityArtifactInfo.proposalArtifact)
                              .flatMap {
                                case (hash, artifact, context) =>
                                  val newStatus = CollectingSignatures[Artifact, Context](
                                    ProposalInfo(artifact, context, hash, majorityArtifactInfo.entropyRates),
                                    majorityTrigger,
                                    candidates,
                                    facilitatorsHash
                                  )
                                  val newState =
                                    state.copy(
                                      status = newStatus,
                                      facilitators = updatedFacilitators,
                                      removedFacilitators = state.removedFacilitators ++ toRemove.toSet
                                    )

                                  val facilitatorAction =
                                    Signature.fromHash(keyPair.getPrivate, hash).flatMap { signature =>
                                      gossip.spread(
                                        ConsensusPeerDeclaration(
                                          state.key,
                                          MajoritySignature(signature, facilitatorsHash)
                                        )
                                      )
                                    }
                                  val followerAction = Applicative[F].unit

                                  val effect =
                                    isFacilitator(updatedFacilitators.toSet)
                                      .pure[F]
                                      .ifM(ifTrue = facilitatorAction, ifFalse = followerAction)

                                  (newState, effect).pure[F]
                              }
                          }
                        }
                      }
                  }
                }
              }

            case CollectingSignatures(majorityArtifactInfo, majorityTrigger, _, ownFacilitatorsHash) =>
              val maybeAllSignatureProposalsAndAbsent =
                extractDeclarations(
                  state,
                  resources,
                  (peerId, pd) => pd.signature.map((peerId, _)),
                  distance >= Patience.signature
                ).filter(_ => previousRoundState.status.isInstanceOf[Finished[Artifact, Context]])

              maybeAllSignatureProposalsAndAbsent.traverseTap {
                case (declarations, _) => warnIfForking(ownFacilitatorsHash)(declarations.map(_._2))
              }.flatMap {
                _.map {
                  case (declarations, absent) =>
                    val signatureProofs = declarations.map {
                      case (id, signature) => SignatureProof(PeerId._Id.get(id), signature.signature)
                    }
                    (signatureProofs, absent)
                }.flatTraverse {
                  case (signatures, absent) =>
                    val (toRemove, updatedFacilitators) = state.facilitators.partition(absent.contains)
                    filterSignaturesWithValidHash(state.key, majorityArtifactInfo.artifactHash, signatures).map { validSignatures =>
                      (majorityArtifactInfo.proposalArtifact.some, NonEmptySet.fromSet(validSignatures.toSortedSet))
                        .mapN(Signed(_, _))
                        .map { signedArtifact =>
                          val newState = state
                            .copy(
                              status = Finished[Artifact, Context](
                                signedArtifact,
                                majorityArtifactInfo.context,
                                majorityTrigger,
                                majorityArtifactInfo.artifactHash,
                                majorityArtifactInfo.entropyRates
                              ),
                              facilitators = updatedFacilitators,
                              removedFacilitators = state.removedFacilitators ++ toRemove.toSet
                            )
                          val effect = consensusFns.consumeSignedMajorityArtifact(signedArtifact, majorityArtifactInfo.context)

                          (newState, effect)
                        }
                    }
                }
              }

            case Finished(_, _, _, _, _) =>
              none[(ConsensusState[Key, Artifact, Context], F[Unit])].pure[F]
            case _ =>
              (state, Applicative[F].unit).some.pure[F]
          }
        }.map { maybeStateAndEffect: Option[(ConsensusState[Key, Artifact, Context], F[Unit])] =>
          maybeStateAndEffect.getOrElse((state, Applicative[F].unit))
        }
      }

    private def extractDeclarations[A](
      state: ConsensusState[Key, Artifact, Context],
      resources: ConsensusResources[Artifact],
      extractor: (PeerId, PeerDeclarations) => Option[A],
      patienceExceeded: Boolean
    ): Option[(List[A], Set[PeerId])] = {
      val (absent, provided) =
        state.facilitators.map { peerId =>
          resources.peerDeclarationsMap
            .get(peerId)
            .flatMap(extractor(peerId, _))
            .toRight(peerId)
        }.separate

      Option.when(absent.isEmpty || patienceExceeded)((provided, absent.toSet))
    }

    private def extractArtifactAndContext(state: ConsensusState[Key, Artifact, Context]): Option[(Artifact, Context)] =
      state.status match {
        case CollectingSignatures(majorityArtifactInfo, _, _, _) =>
          (majorityArtifactInfo.proposalArtifact, majorityArtifactInfo.context).some
        case Finished(signedMajorityArtifact, context, _, _, _) =>
          (signedMajorityArtifact.value, context).some
        case _: CollectingFacilities[_, _] | _: CollectingProposals[_, _] => None
      }

    private def isFacilitator(facilitators: Set[PeerId]): Boolean =
      facilitators.contains(selfId)

    private def filterSignaturesWithValidHash(key: Key, hash: Hash, allSignatures: List[SignatureProof]) =
      allSignatures.filterA { signatureProof =>
        verifySignatureProof(hash, signatureProof)
      }.flatTap { validSignatures =>
        logger
          .warn(
            s"Removed ${(allSignatures.size - validSignatures.size).show} signatures with invalid hash during consensus for key ${key.show}, " +
              s"${validSignatures.size.show} valid signatures left"
          )
          .whenA(allSignatures.size =!= validSignatures.size)
      }

    private def warnIfForking(ownFacilitatorsHash: Hash)(
      declarations: List[PeerDeclaration]
    ): F[Unit] =
      pickMajority(declarations.map(_.facilitatorsHash)).traverse { majorityFacilitatorsHash =>
        logger
          .warn(s"Different facilitators hashes. This node is in fork")
          .whenA(majorityFacilitatorsHash =!= ownFacilitatorsHash)
      }.void

    private def pickMajority[A: Order](proposals: List[A]): Option[A] =
      proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).maximumOption.map(_._2) // todo change to use ent maps

    private def pickValidatedMajority(
      ownProposalInfo: Option[ProposalInfo[Artifact, Context]],
      lastArtifact: Artifact,
      lastContext: Context,
      trigger: ConsensusTrigger,
      candidates: Set[PeerId],
      facilitators: SortedSet[PeerId],
      removed: SortedSet[PeerId]
    )(proposals: List[(Proposal, Artifact)]): F[Option[ProposalInfo[Artifact, Context]]] = {
      val blocks = proposals.map { case (proposal, _) => proposal.peerId -> proposal.blocks.value.toSeq }.toMap
      val entropyRates: Map[PeerId, Double] = entropyRate(blocks)
      val sortedProposals: Seq[(Double, Proposal, Artifact)] = proposals.map {
        case (proposal, artifact) => (entropyRates(proposal.peerId), proposal, artifact)
      }.sortBy { case (score, _, _) => -score }

      def go(
        proposals: List[(Double, Proposal, Artifact)]
      ): F[
        Option[ProposalInfo[Artifact, Context]]
      ] =
        proposals match {
          case (occurrences, proposal, artifact) :: tail =>
            if (ownProposalInfo.exists(artifactInfo => proposal.hash === artifactInfo.artifactHash))
              ownProposalInfo.map(_.copy(entropyRates = entropyRates)).pure[F]
            else
              logger.warn(s"Inspecting next majority artifact candidate with hash=${proposal.hash.show} and occurrences=$occurrences") >>
                consensusFns
                  .validateArtifact(
                    lastArtifact,
                    lastContext,
                    trigger,
                    candidates,
                    SelectActivePeers.selectionInterval,
                    facilitators,
                    removed
                  )(artifact)
                  .flatMap { t: Either[ConsensusFunctions.InvalidArtifact, (Artifact, Context)] =>
                    t.fold(
                      logger.warn(_)(s"Majority artifact candidate with hash=${proposal.hash.show} is invalid!") >>
                        go(tail),
                      result =>
                        ProposalInfo(
                          result._1,
                          result._2,
                          proposal.hash,
                          entropyRates
                        ).some.pure[F]
                    )
                  }
          case Nil => none[ProposalInfo[Artifact, Context]].pure[F]
        }

      go(sortedProposals.toList)
    }

    private def proposalAffinity[A: Order](proposals: List[A], proposal: A): Double =
      if (proposals.nonEmpty)
        proposals.count(Order[A].eqv(proposal, _)).toDouble / proposals.size.toDouble
      else
        0.0
  }

  object Patience {
    val facility: PosInt = 4
    val proposal: PosInt = 8
    val signature: PosInt = 12
  }
}
