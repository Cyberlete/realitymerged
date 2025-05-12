package org.reality

import cats.effect._
import cats.effect.kernel.Async
import cats.syntax.semigroupk._

import org.reality.domain.cell.L0Cell
import org.reality.ext.cats.effect._
import org.reality.http.p2p.L0P2PClient
import org.reality.modules._
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.AppEnvironment.Mainnet
import org.reality.sdk.config.types
import org.reality.sdk.infrastructure.gossip.RumorHandlers
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.trust.handler.trustHandler
import org.reality.security.SecurityProvider

object L0HttpApi2 {

  def mkResources(method: CliMethod, sdk: SDK[IO]): Resource[IO, HttpApi[IO]] = {
    import sdk._
    val cfg: types.AppConfig = method.appConfig
    for {
      _ <- IO.unit.asResource
      p2pClient = L0P2PClient.make[IO](sdk)
      queues <- L0Queues.make[IO](sdk).asResource
      storages <- L0Storages.make[IO](sdk, method).asResource
      services <- L0Services.make[IO](sdk, queues, storages, method).asResource
      programs = L0Programs.make[IO](sdk.sdkPrograms, storages, services)
      healthChecks <- L0HealthChecks.make[IO](sdk, storages, services, programs, p2pClient, method).asResource
      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+> trustHandler(
        storages.trust
      ) <+> services.consensus.handler
      _ <- Daemons.start(storages, services, programs, queues, healthChecks, nodeId, cfg).asResource

      api = mkApi[IO](
        new L0HTTPParams(
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair,
          cfg.environment,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http,
          Nil, // todo pass in endpoint info here
          p2pClient
        )
      )
    } yield api
  }

  def mkApi[F[_]: Async: SecurityProvider: Metrics](params: L0HTTPParams[F]): HttpApi[F] =
    new L0HttpApi[F](
      params.storages,
      params.queues,
      params.services,
      params.programs,
      params.healthchecks,
      params.key,
      Mainnet,
      params.selfId,
      params.nodeVersion,
      params.httpCfg,
      params.coCellRoutes,
      params.p2pClient,
      L0Cell.mkCell(params.queues.l1Output, params.queues.stateChannelOutput)
    )
}

//object Main
//    extends RealityIOApp[Run](
//      name = "dag-l0",
//      header = "Reality Node",
//      version = BuildInfo.version,
//      clusterId = ClusterId(java.util.UUID.fromString("6d7f1d6a-213a-4148-9d45-d7200f555ecf"))
//    ) {
//
//  val opts: Opts[Run] = cli.method.opts
//
//  def run(method: Run, sdk: SDK[IO]): Resource[IO, L0Internals] = {
//    import sdk._
//
//    val cfg = method.appConfig
//
//    for {
//      _ <- IO.unit.asResource
//      p2pClient = L0P2PClient.make[IO](sdk)
//      queues <- L0Queues.make[IO](sdk).asResource
//      storages <- L0Storages.make[IO](sdk, method).asResource
//      services <- L0Services
//        .make[IO](
//          sdk,
//          queues,
//          storages,
//          method
//        )
//        .asResource
//      programs = L0Programs.make[IO](sdk, storages, services)
//      healthChecks <- L0HealthChecks
//        .make[IO](
//          sdk,
//          storages,
//          services,
//          programs,
//          p2pClient,
//          method
//        )
//        .asResource
//
//      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
//        trustHandler(storages.trust) <+> services.consensus.handler
//
//      _ <- Daemons
//        .start(storages, services, programs, queues, healthChecks, nodeId, cfg)
//        .asResource
//
//      api = L0HttpApi2
//        .mkApi[IO](
//          new L0HTTPParams[IO](
//            storages,
//            queues,
//            services,
//            programs,
//            healthChecks,
//            keyPair.getPrivate,
//            cfg.environment,
//            sdk.nodeId,
//            BuildInfo.version,
//            cfg.http,
//            Nil, // todo pass in endpoint info here
//            p2pClient
//          )
//        )
//      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
//      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
//      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)
//
//      gossipDaemon = GossipDaemon.make[IO](
//        storages.rumor,
//        queues.rumor,
//        storages.cluster,
//        p2pClient.gossip,
//        rumorHandler,
//        sdkValidators.rumorValidator,
//        services.localHealthcheck,
//        nodeId,
//        generation,
//        cfg.gossip.daemon,
//        services.collateral
//      )
//
//      _ <- (method match {
//        case _: RunValidator =>
//          gossipDaemon.startAsRegularValidator >>
//            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
//        case m: RunRollback =>
//          storages.node.tryModifyState(
//            NodeState.Initial,
//            NodeState.RollbackInProgress,
//            NodeState.RollbackDone
//          ) {
//            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
//              programs.rollbackLoader.load(m.rollbackHash, accounts.map(a => (a.address, a.balance)).toMap).flatMap {
//                case (snapshotInfo, snapshot) =>
//                  storages.globalSnapshot
//                    .prepend(snapshot, snapshotInfo) >>
//                    services.consensus.manager
//                      .startFacilitatingAfter(snapshot.ordinal, snapshot, snapshotInfo, snapshotInfo, snapshot.ordinal.value.value)
//              }
//            }
//          } >>
//            gossipDaemon.startAsInitialValidator >>
//            services.cluster.createSession >>
//            services.session.createSession >>
//            storages.node.setNodeState(NodeState.Ready)
//        case m: RunGenesis =>
//          storages.node.tryModifyState(
//            NodeState.Initial,
//            NodeState.LoadingGenesis,
//            NodeState.GenesisReady
//          ) {
//            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
//              GlobalSnapshot
//                .mkGenesis[IO](
//                  accounts.map(a => (a.address, a.balance)).toMap,
//                  m.startingEpochProgress
//                )
//                .flatMap {
//                  case (genesis, snapshotInfo: GlobalSnapshotInfo) =>
//                    Signed.forAsyncJson[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
//                      storages.globalSnapshot.prepend(signedGenesis, snapshotInfo) >>
//                        services.collateral
//                          .hasCollateral(sdk.nodeId)
//                          .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
//                        services.consensus.manager
//                          .startFacilitatingAfter(genesis.ordinal, signedGenesis, snapshotInfo, snapshotInfo, genesis.ordinal.value.value)
//                    }
//                }
//            }
//          } >>
//            gossipDaemon.startAsInitialValidator >>
//            services.cluster.createSession >>
//            services.session.createSession >>
//            storages.node.setNodeState(NodeState.Ready)
//      }).asResource
//    } yield
//      L0Internals(
//        p2pClient = p2pClient,
//        queues = queues,
//        storages = storages,
//        services = services,
//        programs = programs,
//        healthChecks = healthChecks,
//        api = api,
//        gossipDaemon = gossipDaemon,
//        sdk = sdk
//      )
//  }
//}
