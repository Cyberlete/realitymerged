package org.reality.sdk.domain.healthcheck.consensus.types

import cats.effect.Concurrent

import org.reality.ext.codecs.BinaryCodec
import org.reality.schema.peer.PeerId

import io.circe.{Decoder, Encoder}
import org.http4s.{EntityDecoder, EntityEncoder}

trait ConsensusHealthStatus[K <: HealthCheckKey, A <: HealthCheckStatus] {
  def key: K
  def roundIds: Set[HealthCheckRoundId]
  def owner: PeerId
  def status: A
  def clusterState: Set[PeerId]
}

object ConsensusHealthStatus {
  implicit def encoder[F[_], K <: HealthCheckKey, A <: HealthCheckStatus](
    implicit encoder: Encoder[ConsensusHealthStatus[K, A]]
  ): EntityEncoder[F, ConsensusHealthStatus[K, A]] =
    BinaryCodec.encoder[F, ConsensusHealthStatus[K, A]]

  implicit def decoder[F[_]: Concurrent, K <: HealthCheckKey, A <: HealthCheckStatus](
    implicit decoder: Decoder[ConsensusHealthStatus[K, A]]
  ): EntityDecoder[F, ConsensusHealthStatus[K, A]] = BinaryCodec.decoder[F, ConsensusHealthStatus[K, A]]
}
