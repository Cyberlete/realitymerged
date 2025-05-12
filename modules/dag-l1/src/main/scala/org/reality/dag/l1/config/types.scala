package org.reality.dag.l1.config

import scala.concurrent.duration.{DAYS, DurationInt, FiniteDuration}

import org.reality.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.io.file.Path

object types {

  case class L1AppConfig(
    override val environment: AppEnvironment,
    override val http: HttpConfig,
    override val db: DBConfig,
    override val gossip: GossipConfig,
    consensus: ConsensusConfig,
    override val healthCheck: HealthCheckConfig,
    override val collateral: CollateralConfig
  ) extends AppConfig(
        environment,
        http,
        db,
        gossip,
        TrustConfig(TrustDaemonConfig(FiniteDuration.apply(1L, DAYS))),
        healthCheck,
        SnapshotConfig(
          org.reality.sdk.config.types
            .ConsensusConfig(10.seconds, 10.seconds, 10.seconds, ObservationConfig(10.seconds, 10.seconds, 1L), SelectPeersConfig(4, 4)),
          globalSnapshotPath = Path("data/snapshot"),
          inMemoryCapacity = 10L
        ),
        collateral,
        RewardsConfig()
      )

  case class L1DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  ) extends DBConfig(driver, url, user, password)
}
