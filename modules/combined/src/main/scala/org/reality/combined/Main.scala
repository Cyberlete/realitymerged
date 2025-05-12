package org.reality.combined

import org.reality.combined.examples.CombinedMonoidExample

object Main extends CombinedMonoidExample
//object Main extends IOApp {
//  private val logger = LoggerFactory.getLogger(this.getClass)
//  val args: List[String] = Nil
//  logger.info(s"Initial arguments: $args")
//
//  val isNodeApi: Boolean = args.contains("--node-api")
//  logger.info(s"Is Node API: $isNodeApi")
//
//  val (l0Command, l0Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l0"), "--command", "run-genesis")
//  logger.info(s"L0 Command: $l0Command, L0 Args: $l0Args")
//
//  val (l1Command, l1Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l1"), "--command", "run-initial-validator")
//  logger.info(s"L1 Command: $l1Command, L1 Args: $l1Args")
//
//  val l0ArgsWithCommand: List[String] = l0Command :: l0Args
//  val l1ArgsWithCommand: List[String] = l1Command :: l1Args
//
//  val nodeApiSetup: IO[Option[(Host, Port)]] = if (isNodeApi) {
//    for {
//      apiHostStr <- envVarOrError("API_HOST")
//      apiPortStr <- envVarOrError("API_PORT")
//      apiHost <- Host.fromString(apiHostStr) match {
//        case Some(host) => IO.pure(host)
//        case None       => IO.raiseError(new Exception(s"Invalid API_HOST: $apiHostStr"))
//      }
//      apiPort <- Port.fromString(apiPortStr) match {
//        case Some(port) => IO.pure(port)
//        case None       => IO.raiseError(new Exception(s"Invalid API_PORT: $apiPortStr"))
//      }
//    } yield {
//      logger.info(s"Node API setup: Host - $apiHostStr, Port - $apiPortStr")
//      Some((apiHost, apiPort))
//    }
//  } else {
//    IO.pure(None)
//  }
//
//  val program = for {
//    _ <- IO(logger.info(s"L0 Args with Command: $l0ArgsWithCommand"))
//    _ <- IO(logger.info(s"L1 Args with Command: $l1ArgsWithCommand"))
//    (l0Resource: Resource[IO, NodeInternals], _) <- L0Helper.setup(l0ArgsWithCommand)
//    (l1Resource, _) <- L1Helper.setup(l1ArgsWithCommand)
//    nodeApiConfig <- nodeApiSetup
//    result <- nodeApiConfig match {
//      case Some((apiHost, apiPort)) =>
//        l0Resource.use { l0Internals =>
//          l1Resource.use { l1Internals =>
//            for {
//              api <- NodeApi.make(l0Internals, l1Internals)
//              httpApi = CombinedApi(api)
//              exitCode <- MkHttpServer[IO]
//                .newEmber(ServerName("api"), HttpServerConfig(apiHost, apiPort, 1.second), httpApi.app)
//                .useForever
//                .as(ExitCode.Success)
//            } yield exitCode
//          }
//        }
//      case None =>
//        val runtime = List(l0Resource, l1Resource).reduce((lr, lt) => lr >> lt) // l0Resource >> l1Resource
//        for {
//          code <- Stream.eval(runtime.useForever).compile.drain.as(ExitCode.Success)
//        } yield code
//    }
//  } yield result
//
//  override def run(args: List[String]): IO[ExitCode] = {
//    // Logging the initial arguments
//    logger.info(s"Initial arguments: $args")
//
//    val isNodeApi: Boolean = args.contains("--node-api")
//    logger.info(s"Is Node API: $isNodeApi")
//
//    val (l0Command, l0Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l0"), "--command", "run-genesis")
//    logger.info(s"L0 Command: $l0Command, L0 Args: $l0Args")
//
//    val (l1Command, l1Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l1"), "--command", "run-initial-validator")
//    logger.info(s"L1 Command: $l1Command, L1 Args: $l1Args")
//
//    val l0ArgsWithCommand: List[String] = l0Command :: l0Args
//    val l1ArgsWithCommand: List[String] = l1Command :: l1Args
//
//    val nodeApiSetup: IO[Option[(Host, Port)]] = if (isNodeApi) {
//      for {
//        apiHostStr <- envVarOrError("API_HOST")
//        apiPortStr <- envVarOrError("API_PORT")
//        apiHost <- Host.fromString(apiHostStr) match {
//          case Some(host) => IO.pure(host)
//          case None       => IO.raiseError(new Exception(s"Invalid API_HOST: $apiHostStr"))
//        }
//        apiPort <- Port.fromString(apiPortStr) match {
//          case Some(port) => IO.pure(port)
//          case None       => IO.raiseError(new Exception(s"Invalid API_PORT: $apiPortStr"))
//        }
//      } yield {
//        logger.info(s"Node API setup: Host - $apiHostStr, Port - $apiPortStr")
//        Some((apiHost, apiPort))
//      }
//    } else {
//      IO.pure(None)
//    }
//
//    val program = for {
//      _ <- IO(logger.info(s"L0 Args with Command: $l0ArgsWithCommand"))
//      _ <- IO(logger.info(s"L1 Args with Command: $l1ArgsWithCommand"))
//      (l0Resource: Resource[IO, NodeInternals], _) <- L0Helper.setup(l0ArgsWithCommand)
//      (l1Resource, _) <- L1Helper.setup(l1ArgsWithCommand)
//      nodeApiConfig <- nodeApiSetup
//      result <- nodeApiConfig match {
//        case Some((apiHost, apiPort)) =>
//          l0Resource.use { l0Internals =>
//            l1Resource.use { l1Internals =>
//              for {
//                api <- NodeApi.make(l0Internals, l1Internals)
//                httpApi = CombinedApi(api)
//                exitCode <- MkHttpServer[IO]
//                  .newEmber(ServerName("api"), HttpServerConfig(apiHost, apiPort, 1.second), httpApi.app)
//                  .useForever
//                  .as(ExitCode.Success)
//              } yield exitCode
//            }
//          }
//        case None =>
//          val runtime = List(l0Resource, l1Resource).reduce((lr, lt) => lr >> lt) // l0Resource >> l1Resource
//          for {
//            code <- Stream.eval(runtime.useForever).compile.drain.as(ExitCode.Success)
//          } yield code
//      }
//    } yield result
//
//    program.handleErrorWith { error =>
//      logger.error("An error occurred", error)
//      IO.pure(ExitCode.Error)
//    }
//  }
//}
