package org.reality.sdk.config

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.schema.address.Address
import org.reality.schema.balance.Amount
import org.reality.schema.node.NodeState
import org.reality.sdk.config.AppEnvironment

import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.io.file.Path

object types {

  case class SdkConfig(
    environment: AppEnvironment,
    gossipConfig: GossipConfig,
    httpConfig: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: CollateralConfig
  )

  case class RumorStorageConfig(
    peerRumorsCapacity: PosLong,
    activeCommonRumorsCapacity: NonNegLong,
    seenCommonRumorsCapacity: NonNegLong
  )

  case class GossipDaemonConfig(
    peerRound: GossipRoundConfig,
    commonRound: GossipRoundConfig
  )

  case class GossipRoundConfig(
    fanout: PosInt,
    interval: FiniteDuration,
    maxConcurrentRounds: PosInt
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    lockDuration: FiniteDuration,
    observation: ObservationConfig,
    selectPeers: SelectPeersConfig = SelectPeersConfig(4, 4)
  )

  case class ObservationConfig(
    interval: FiniteDuration,
    timeout: FiniteDuration,
    offset: NonNegLong
  )

  case class SelectPeersConfig(activePeersSize: PosInt, selectionInterval: PosInt)

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
  )

  case class HealthCheckConfig(
    ping: PingHealthCheckConfig,
    removeUnresponsiveParallelPeersAfter: FiniteDuration,
    requestProposalsAfter: FiniteDuration
  )

  case class PingHealthCheckConfig(
    enabled: Boolean,
    concurrentChecks: PosInt,
    defaultCheckTimeout: FiniteDuration,
    defaultCheckAttempts: PosInt,
    ensureCheckInterval: FiniteDuration
  )

  case class CollateralConfig(
    amount: Amount
  )

  type Percentage = Int Refined Interval.Closed[0, 100]

  class AppConfig(
    val environment: AppEnvironment,
    val http: HttpConfig,
    val db: DBConfig,
    val gossip: GossipConfig,
    val trust: TrustConfig,
    val healthCheck: HealthCheckConfig,
    val snapshot: SnapshotConfig,
    val collateral: CollateralConfig,
    val rewards: RewardsConfig,
    val consensusConfig: ConsensusConfig = ConsensusConfig(
      timeTriggerInterval = 43.seconds,
      declarationTimeout = 50.seconds,
      lockDuration = 10.seconds,
      observation = ObservationConfig(
        interval = 10.seconds,
        timeout = 10.minutes,
        offset = 2L
      )
//      ,
//      selectPeers = SelectPeersConfig(activePeersSize = 3, selectionInterval = 5)
    )
  )

  case class L0DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  ) extends DBConfig(driver, url, user, password)
  abstract class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    globalSnapshotPath: Path,
    inMemoryCapacity: NonNegLong
  )

  case class SoftStakingAndTestnetConfig(
    softStakeAddress: Address = Address("NET77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn"),
    testnetAddress: Address = Address("NET0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P"),
    startingOrdinal: EpochProgress = EpochProgress(0L),
    testnetCount: NonNegLong = 75L,
    testnetWeight: NonNegLong = 4L,
    softStakeCount: NonNegLong = 5562L,
    softStakeWeight: NonNegLong = 4L,
    facilitatorWeight: NonNegLong = 6L
  )

  case class DTMConfig(
    address: Address = Address("NET0Njmo6JZ3FhkLsipJSppepUHPuTXcSifARfvK"),
    dtmWeight: NonNegLong = 195L,
    remainingWeight: NonNegLong = 805L
  )

  case class StardustConfig(
    addressPrimary: Address = Address("NETSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS"),
    addressSecondary: Address = Address("NET8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY"),
    primaryWeight: NonNegLong = 1L,
    secondaryWeight: NonNegLong = 1L,
    remainingWeight: NonNegLong = 18L
  )

  case class RewardsConfig(
    softStaking: SoftStakingAndTestnetConfig = SoftStakingAndTestnetConfig(),
    dtm: DTMConfig = DTMConfig(),
    stardust: StardustConfig = StardustConfig(),
    rewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
      EpochProgress(1296000L) -> Amount(658_43621389L),
      EpochProgress(2592000L) -> Amount(329_21810694L),
      EpochProgress(3888000L) -> Amount(164_60905347L),
      EpochProgress(5184000L) -> Amount(82_30452674L)
    )
  )
}
