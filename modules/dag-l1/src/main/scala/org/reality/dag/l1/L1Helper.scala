//package org.reality.dag.l1
//
//import cats.effect
//import cats.effect.IO
//import org.reality.BuildInfo
//import org.reality.dag.l1.cli.method
//import org.reality.schema.cluster.ClusterId
//import org.reality.sdk.app.{NodeInternals, RealityBootstrap}
//import com.monovore.decline.Command
//import fs2.concurrent.SignallingRef
//
//object L1Helper {
//  def setup(args: List[String]): IO[(effect.Resource[IO, NodeInternals], SignallingRef[IO, Unit])] = {
//    val command = Command("L1Command", "Run L1")(method.opts).parse(args)
//
//    command match {
//      case Right(l1Config) =>
//        RealityBootstrap.commonBootstrap[method.Run](
//          name = "net-l1",
//          header = "NET L1 node",
//          version = BuildInfo.version,
//          clusterId = ClusterId(java.util.UUID.fromString("17e78993-37ea-4539-a4f3-039068ea1e92")),
//          run = Main.run,
//          method = l1Config
//        )
//
//      case Left(help) =>
//        IO.raiseError(new Exception(help.toString()))
//    }
//  }
//}
