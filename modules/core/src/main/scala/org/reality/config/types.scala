//package org.reality.config
//
//import scala.collection.immutable.SortedMap
//
//import org.reality.dag.snapshot.epoch.EpochProgress
//import org.reality.schema.address.Address
//import org.reality.schema.balance.Amount
//import org.reality.sdk.config.AppEnvironment
//import org.reality.sdk.config.types._
//
//import ciris.Secret
//import eu.timepit.refined.api.Refined
//import eu.timepit.refined.auto._
//import eu.timepit.refined.numeric.Interval
//import eu.timepit.refined.types.numeric._
//import eu.timepit.refined.types.string.NonEmptyString
//import fs2.io.file.Path
//
//object types {
//  type Percentage = Int Refined Interval.Closed[0, 100]
//
//  case class AppConfig(
//    environment: AppEnvironment,
//    http: HttpConfig,
//    db: DBConfig,
//    gossip: GossipConfig,
//    trust: TrustConfig,
//    healthCheck: HealthCheckConfig,
//    snapshot: SnapshotConfig,
//    collateral: CollateralConfig,
//    rewards: RewardsConfig
//  )
//
//  case class DBConfig(
//    driver: NonEmptyString,
//    url: NonEmptyString,
//    user: NonEmptyString,
//    password: Secret[String]
//  )
//
//  case class TrustConfig(
//    daemon: TrustDaemonConfig
//  )
//
//  case class SnapshotConfig(
//    consensus: ConsensusConfig,
//    globalSnapshotPath: Path,
//    inMemoryCapacity: NonNegLong
//  )
//
//  case class SoftStakingAndTestnetConfig(
//    softStakeAddress: Address = Address("NET77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn"),
//    testnetAddress: Address = Address("NET0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P"),
//    startingOrdinal: EpochProgress = EpochProgress(0L),
//    testnetCount: NonNegLong = 75L,
//    testnetWeight: NonNegLong = 4L,
//    softStakeCount: NonNegLong = 5562L,
//    softStakeWeight: NonNegLong = 4L,
//    facilitatorWeight: NonNegLong = 6L
//  )
//
//  case class DTMConfig(
//    address: Address = Address("NET0Njmo6JZ3FhkLsipJSppepUHPuTXcSifARfvK"),
//    dtmWeight: NonNegLong = 195L,
//    remainingWeight: NonNegLong = 805L
//  )
//
//  case class StardustConfig(
//    addressPrimary: Address = Address("NETSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS"),
//    addressSecondary: Address = Address("NET8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY"),
//    primaryWeight: NonNegLong = 1L,
//    secondaryWeight: NonNegLong = 1L,
//    remainingWeight: NonNegLong = 18L
//  )
//
//  case class RewardsConfig(
//    softStaking: SoftStakingAndTestnetConfig = SoftStakingAndTestnetConfig(),
//    dtm: DTMConfig = DTMConfig(),
//    stardust: StardustConfig = StardustConfig(),
//    rewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
//      EpochProgress(1296000L) -> Amount(658_43621389L),
//      EpochProgress(2592000L) -> Amount(329_21810694L),
//      EpochProgress(3888000L) -> Amount(164_60905347L),
//      EpochProgress(5184000L) -> Amount(82_30452674L)
//    )
//  )
//}
