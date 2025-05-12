//package org.reality
//
//import cats.effect
//import cats.effect.IO
//
//import org.reality.cli.method
//import org.reality.schema.cluster.ClusterId
//import org.reality.sdk.app.{NodeInternals, RealityBootstrap}
//
//import com.monovore.decline.Command
//import fs2.concurrent.SignallingRef
//
//object L0Helper {
//  def setup(args: List[String]): IO[(effect.Resource[IO, NodeInternals], SignallingRef[IO, Unit])] = {
//    val command = Command("L0Command", "Run L0")(method.opts).parse(args)
//
//    command match {
//      case Right(l0Config) =>
//        RealityBootstrap.commonBootstrap[method.Run](
//          name = "net-l0",
//          header = "Reality Node",
//          version = BuildInfo.version,
//          clusterId = ClusterId(java.util.UUID.fromString("6d7f1d6a-213a-4148-9d45-d7200f555ecf")),
//          run = Main.run,
//          method = l0Config
//        )
//
//      case Left(help) =>
//        IO.raiseError(new Exception(help.toString()))
//    }
//  }
//}
