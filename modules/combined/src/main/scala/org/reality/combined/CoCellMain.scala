package org.reality.combined

import cats.effect.kernel.Async
import cats.effect.{IO, Resource}
import cats.implicits.{catsSyntaxApplicativeErrorId, toSemigroupKOps}
import cats.syntax.applicative._

import org.reality.cli.method.{RunGenesis, RunRollback, RunValidator}
import org.reality.dag.l1.{MkStateChannel, StateChannel, WasmExecutionParams}
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.domain.cell.L0Cell
import org.reality.ext.cats.effect.ResourceIO
import org.reality.http.p2p.{L0P2PClient, P2PClient}
import org.reality.infrastructure.genesis.{Loader => GenesisLoader}
import org.reality.kernel._
import org.reality.modules._
import org.reality.schema.cluster.ClusterId
import org.reality.schema.node.NodeState
import org.reality.sdk.app.{NodeInternals, RealityIOApp, SDK}
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.AppEnvironment.Mainnet
import org.reality.sdk.config.types
import org.reality.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.trust.handler.trustHandler
import org.reality.sdk.resources.MkHttpServer
import org.reality.sdk.resources.MkHttpServer.ServerName
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.{BuildInfo, OwnCollateralNotSatisfied}

import com.monovore.decline.Opts
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait MkHttpApi {
  def mkResources(method: CliMethod, sdk: SDK[IO]): Resource[IO, HttpApi[IO]]
}

object L0HttpApiObj extends MkHttpApi {

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

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        sdkValidators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      _ <- Slf4jLogger.getLogger[IO].error(s"Run validator is starting ${method.isInstanceOf[RunValidator]}").asResource

      _ <- (method match {
        case r: RunValidator =>
          Slf4jLogger.getLogger[IO].error("Run validator is starting") >>
            gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
              programs.rollbackLoader.load(m.rollbackHash, accounts.map(a => (a.address, a.balance)).toMap).flatMap {
                case (snapshotInfo, snapshot) =>
                  storages.globalSnapshot
                    .prepend(snapshot, snapshotInfo) >>
                    services.consensus.manager
                      .startFacilitatingAfter(snapshot.ordinal, snapshot, snapshotInfo, snapshotInfo, snapshot.ordinal.value.value)
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
        case m: RunGenesis =>
          Slf4jLogger.getLogger[IO].error(s"RunGenesis begin") >>
            storages.node.tryModifyState(
              NodeState.Initial,
              NodeState.LoadingGenesis,
              NodeState.GenesisReady
            ) {
              GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
                GlobalSnapshot
                  .mkGenesis[IO](
                    accounts.map(a => (a.address, a.balance)).toMap,
                    m.startingEpochProgress
                  )
                  .flatMap {
                    case (genesis, snapshotInfo: GlobalSnapshotInfo) =>
                      Signed.forAsyncJson[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
                        storages.globalSnapshot.prepend(signedGenesis, snapshotInfo) >>
                          services.collateral
                            .hasCollateral(sdk.nodeId)
                            .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                          services.consensus.manager
                            .startFacilitatingAfter(genesis.ordinal, signedGenesis, snapshotInfo, snapshotInfo, genesis.ordinal.value.value)
                      }
                  }
              }
            } >> Slf4jLogger.getLogger[IO].error(s"RunGenesis state modified") >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            storages.node.getNodeState.flatMap(ns => Slf4jLogger.getLogger[IO].error(s"Genesis Node State: ${ns} validator is starting"))
        case _ => IO.pure(())
      }).asResource

      api = L0HttpApiObj.mkApi[IO](
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
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)
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
case class CoCellInternals(
  p2pClient: P2PClient[IO],
  queues: Queues[IO],
  storages: Storages[IO],
  services: Services[IO],
  programs: Programs[IO],
  healthChecks: HealthChecks[IO],
  api: HttpApi[IO],
  sdk: SDK[IO],
  apiCell: Ω => Cell[IO, StackF, Ω, Ω, Either[CellError, Ω]],
  stateChannel: Option[StateChannel[IO, _, _]]
) extends NodeInternals

abstract class CoCellMain[A <: CliMethod]
    extends RealityIOApp[A](
      "CoCellMain",
      "CoCellMain",
      ClusterId(java.util.UUID.fromString("17e78993-37ea-4539-a4f3-039068ea1e92")),
      version = BuildInfo.version
    ) {
  val opts: Opts[A]
  val sc: Option[MkStateChannel] // todo make sure composed before here
  val coCellInst: CoCell
  val mkApi: (A, SDK[IO]) => Resource[IO, HttpApi[IO]]
  val wasmPrograms: List[WasmExecutionParams[_, _]]

  println("within CoCellMain")
  def run(
    method: A,
    sdk: SDK[IO]
  ): Resource[IO, CoCellInternals] = { // todo put statechannel make and http endpoints in SDK
    import sdk._
    val cfg = method.appConfig
    logger.info("Starting CoCellMain")
    println("within CoCellMain.run")

    logger.info(
      s"HTTP Configuration: Public HTTP Host: ${cfg.http.publicHttp.host} Public HTTP Port: ${cfg.http.publicHttp.port}"
    )
    sc match {
      case Some(mkChannel) =>
        for {
          _ <- logger.info("Has state channel").asResource
          (api, validators) <- mkChannel.mkApi[A](method, sdk)
          stateChannel <- mkChannel
            .make[IO](
              cfg,
              api.key,
              api.p2pClient,
              api.programs,
              api.queues,
              api.selfId,
              api.services,
              api.storages,
              validators,
              mkChannel.cellObj,
              wasmPrograms
            )
            .asResource
          _ <- stateChannel.runtime.compile.drain.handleErrorWith { error =>
            Slf4jLogger.getLogger[IO].error(error)("An error occurred during state channel runtime") >> error.raiseError[IO, Unit]
          }.background
        } yield
          CoCellInternals(
            p2pClient = api.p2pClient,
            queues = api.queues,
            storages = api.storages,
            services = api.services,
            programs = api.programs,
            healthChecks = api.healthchecks,
            api = api,
            sdk = sdk,
            apiCell = api.mkCell,
            stateChannel = Some(stateChannel)
          )
      case None =>
        for {
          _ <- logger.info("No state channel").asResource
          api <- mkApi(method, sdk)
        } yield
          CoCellInternals(
            p2pClient = api.p2pClient,
            queues = api.queues,
            storages = api.storages,
            services = api.services,
            programs = api.programs,
            healthChecks = api.healthchecks,
            api = api,
            sdk = sdk,
            apiCell = api.mkCell,
            stateChannel = None
          )
    }
  }
}
