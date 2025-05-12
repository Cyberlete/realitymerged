package org.reality.sdk.app

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}

import org.reality.schema.generation.Generation
import org.reality.schema.peer.PeerId
import org.reality.sdk.http.p2p.SdkP2PClient
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.modules._
import org.reality.sdk.resources.SdkResources
import org.reality.security.SecurityProvider

import fs2.concurrent.SignallingRef

trait SDK[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val metrics: Metrics[F]
  implicit val supervisor: Supervisor[F]

  val keyPair: KeyPair
  lazy val nodeId = PeerId.fromPublic(keyPair.getPublic)
  val generation: Generation
  val seedlist: Option[Set[PeerId]]

  val sdkResources: SdkResources[F]
  val sdkP2PClient: SdkP2PClient[F]
  val sdkQueues: SdkQueues[F]
  val sdkStorages: SdkStorages[F]
  val sdkServices: SdkServices[F]
  val sdkPrograms: SdkPrograms[F]
  val sdkValidators: SdkValidators[F]

  def restartSignal: SignallingRef[F, Unit]
}
