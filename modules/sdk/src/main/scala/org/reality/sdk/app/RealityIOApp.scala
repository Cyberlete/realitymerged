package org.reality.sdk.app

import cats.effect._

import org.reality.schema.cluster.ClusterId
import org.reality.sdk.cli.CliMethod

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import eu.timepit.refined.auto._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class NodeInternals

abstract class RealityIOApp[A <: CliMethod](
  name: String,
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean = true,
  version: String = ""
) extends CommandIOApp(
      name,
      header,
      helpFlag,
      version
    ) {

//  val thing = L0Cell

  /** Command-line opts
    */
  def opts: Opts[A]

  protected val logger = Slf4jLogger.getLogger[IO]

  def run(method: A, sdk: SDK[IO]): Resource[IO, NodeInternals]

  override final def main: Opts[IO[ExitCode]] =
    RealityBootstrap
      .bootstrap(
        name = name,
        header = header,
        clusterId = clusterId,
        helpFlag = helpFlag,
        version = version,
        opts = opts,
        run = run
      )
      .map { ioTuple: IO[(Resource[IO, NodeInternals], SignallingRef[IO, Unit])] =>
        ioTuple.flatMap {
          case (startup, _restartSignal) =>
            _restartSignal.discrete.switchMap { _ =>
              Stream.eval(startup.useForever)
            }.compile.drain.as(ExitCode.Success)
        }
      }

}
