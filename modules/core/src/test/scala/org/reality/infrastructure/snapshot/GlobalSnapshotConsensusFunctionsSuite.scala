package org.reality.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}
import org.reality.dag.block.processing._
import org.reality.net.snapshot._
import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.domain.rewards.Rewards
import org.reality.domain.snapshot.GlobalSnapshotStorage
import org.reality.ext.cats.syntax.next.catsSyntaxNext
import org.reality.keytool.KeyPairGenerator
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction._
import org.reality.schema.{ID, SnapshotOrdinal}
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.infrastructure.consensus.trigger.EventTrigger
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.Signed
import org.reality.security.signature.Signed.forAsyncJson
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.reality.syntax.sortedCollection._
import eu.timepit.refined.auto._
import org.reality.dag.domain.block.NETBlock
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      SecurityProvider.forAsync[IO].flatMap { sp =>
        Metrics.forAsync[IO](Seq.empty).map((supervisor, sp, _))
      }
    }

  val gss: GlobalSnapshotStorage[IO] = new GlobalSnapshotStorage[IO] {

    override def head: IO[Option[(Signed[GlobalSnapshot], GlobalSnapshotInfo)]] = ???

    override def prepend(snapshot: Signed[GlobalSnapshot], state: GlobalSnapshotInfo): IO[Boolean] = ???

    override def headSnapshot: IO[Option[Signed[GlobalSnapshot]]] = ???

    override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalSnapshot]]] = ???

    override def get(hash: Hash): IO[Option[Signed[GlobalSnapshot]]] = ???

    override def getDeployAppTransaction(appIdentifier: String): IO[Option[DeployAppTransactionInfo]] = ???

    override def getAppProvider(appIdentifier: String): IO[Option[RegisterAppProviderTransactionInfo]] = ???

    override def getRange(start: SnapshotOrdinal, end: SnapshotOrdinal): IO[List[Signed[GlobalSnapshotArtifact]]] = ???
  }

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(blocks: List[Signed[NETBlock]], context: BlockAcceptanceContext[IO]): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[NETBlock],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor = new GlobalSnapshotStateChannelEventsProcessor[IO] {

    override def process(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelOutput]
    ): IO[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])] =
      IO((events.groupByNel(_.address).view.mapValues(_.map(_.snapshot)).toSortedMap, Set.empty))

    // def process(
    //   lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    //   events: List[StateChannelOutput]
    // ): IO[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[GlobalSnapshotEvent])] = IO(
    //   (events.groupByNel(_.address).view.mapValues(_.map(_.snapshot)).toSortedMap, Set.empty)
    // )
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[IO] = new Rewards[IO] {

    override def mintedDistribution(
      epochProgress: EpochProgress,
      facilitators: SortedSet[ID.Id],
      entRates: Map[PeerId, Double]
    ): IO[SortedSet[RewardTransaction]] = ???

    override def feeDistribution(
      snapshotOrdinal: SnapshotOrdinal,
      transactions: SortedSet[Transaction],
      facilitators: SortedSet[ID.Id],
      entRates: Map[PeerId, Double]
    ): IO[SortedSet[RewardTransaction]] = IO(SortedSet.empty)

    override def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount =
      ???

  }

  val env: AppEnvironment = AppEnvironment.Testnet

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, sp, m) = res

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val gscf = GlobalSnapshotConsensusFunctions.make(gss, bam, scProcessor, collateral, rewards, env)

      GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue, PeerId.fromPublic(keyPair.getPublic)).flatMap {
        case (genesis, state) =>
          Signed.forAsyncJson[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
            mkStateChannelEvent().flatMap { scEvent =>
              gscf
                .createProposalArtifact(
                  SnapshotOrdinal.MinValue,
                  signedGenesis,
                  state,
                  EventTrigger,
                  Set(scEvent.asLeft[NETEvent]),
                  SortedMap.empty,
                  Set.empty,
                  signedGenesis.proofs.toSortedSet.map(sp => PeerId.fromId(sp.id)),
                  Set.empty,
                  None
                )
                .flatMap {
                  case (artifact, _, _, _) =>
                    gscf
                      .validateArtifact(
                        signedGenesis,
                        state,
                        EventTrigger,
                        Set.empty,
                        1,
                        signedGenesis.proofs.toSortedSet.map(sp => PeerId.fromId(sp.id)),
                        Set.empty
                      )(artifact)
                      .map { result =>
                        expect.same(result.isRight, true) && expect
                          .same(result.map(_._1.stateChannelSnapshots(scEvent.address)), Right(NonEmptyList.one(scEvent.snapshot)))
                      }
                }
            }
          }
      }
    }
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, sp, m) = res

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val gscf = GlobalSnapshotConsensusFunctions.make(gss, bam, scProcessor, collateral, rewards, env)

      GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue, PeerId.fromPublic(keyPair.getPublic)).flatMap {
        case (genesis, state) =>
          Signed.forAsyncJson[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
            mkStateChannelEvent().flatMap {
              case scEvent =>
                gscf
                  .createProposalArtifact(
                    SnapshotOrdinal.MinValue,
                    signedGenesis,
                    state,
                    EventTrigger,
                    Set(scEvent.asLeft[NETEvent]),
                    SortedMap.empty,
                    Set.empty,
                    signedGenesis.proofs.toSortedSet.map(sp => PeerId.fromId(sp.id)),
                    Set.empty,
                    None
                  )
                  .flatMap {
                    case (artifact, _, _, _) =>
                      gscf
                        .validateArtifact(
                          signedGenesis,
                          state,
                          EventTrigger,
                          Set.empty,
                          1,
                          signedGenesis.proofs.toSortedSet.map(sp => PeerId.fromId(sp.id)),
                          Set.empty
                        )(
                          artifact.copy(ordinal = artifact.ordinal.next)
                        )
                        .map { result =>
                          expect.same(result.isLeft, true)
                        }
                  }
            }
          }
      }
    }
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
    signedSC <- forAsyncJson(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
