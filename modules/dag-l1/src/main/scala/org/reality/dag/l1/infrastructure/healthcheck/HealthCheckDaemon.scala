package org.reality.dag.l1.infrastructure.healthcheck

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._

import scala.concurrent.duration._

import org.reality.dag.l1.modules.L1HealthChecks
import org.reality.sdk.domain.Daemon

import fs2._

trait HealthCheckDaemon[F[_]] extends Daemon[F] {}

object HealthCheckDaemon {

  def make[F[_]: Async](healthChecks: L1HealthChecks[F])(implicit S: Supervisor[F]): HealthCheckDaemon[F] = new HealthCheckDaemon[F] {

    def start: F[Unit] =
      S.supervise(periodic).void

    private def periodic: F[Unit] =
      Stream
        .awakeEvery(10.seconds)
        .evalTap { _ =>
          healthChecks.ping.trigger()
        }
        .compile
        .drain
  }
}
