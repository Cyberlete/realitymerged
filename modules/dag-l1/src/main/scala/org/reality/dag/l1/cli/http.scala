package org.reality.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration._

import org.reality.sdk.cli.http._
import org.reality.sdk.config.types.{HttpClientConfig, HttpConfig, HttpServerConfig}

import com.comcast.ip4s.IpLiteralSyntax
import com.monovore.decline.Opts

object http {

  val client = HttpClientConfig(
    timeout = 60.seconds,
    idleTimeInPool = 30.seconds
  )

  val opts: Opts[HttpConfig] =
    (
      externalIpOpts.withDefault(host"127.0.0.1"),
      publicHttpPortOpts.withDefault(port"9010"),
      p2pHttpPortOpts.withDefault(port"9011"),
      cliHttpPortOpts.withDefault(port"9012")
    ).mapN((externalIp, publicPort, p2pPort, cliPort) =>
      HttpConfig(
        externalIp,
        client,
        HttpServerConfig(host"0.0.0.0", publicPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"0.0.0.0", p2pPort, shutdownTimeout = 1.second),
        HttpServerConfig(host"127.0.0.1", cliPort, shutdownTimeout = 1.second)
      )
    )

}
