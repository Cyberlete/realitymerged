package org.reality.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.reality.BuildInfo
import org.reality.dag.l1.app.AppContext
import org.reality.dag.l1.cli.Run
import org.reality.dag.l1.cli.method.{RunInitialValidator, RunValidator}
import org.reality.dag.l1.domain.consensus.block.BlockConsensusCell
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.reality.dag.l1.modules._
import org.reality.ext.cats.effect.ResourceIO
import org.reality.schema.cluster.ClusterId
import org.reality.schema.node.NodeState
import org.reality.schema.node.NodeState.SessionStarted
import org.reality.sdk.app.{RealityIOApp, SDK}
import org.reality.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.reality.sdk.resources.MkHttpServer
import org.reality.sdk.resources.MkHttpServer.ServerName

import com.monovore.decline.Opts
object Main
    extends RealityIOApp[Run](
      "dag-l1",
      "DAG L1 node",
      ClusterId(java.util.UUID.fromString("17e78993-37ea-4539-a4f3-039068ea1e92")),
      version = BuildInfo.version
    ) {
  val opts: Opts[Run] = cli.method.opts

  def run(
    method: Run,
    sdk: SDK[IO]
  ): Resource[IO, L1Internals] = { // todo put statechannel make and http endpoints in SDK
    import sdk._

    val cfg = method.appConfig

    logger.info("Starting application L1...")

    logger.info(
      s"HTTP Configuration: Public HTTP Host: ${cfg.http.publicHttp.host} Public HTTP Port: ${cfg.http.publicHttp.port}"
    )

    for {
      queues <- L1Queues.make[IO](sdk).asResource
      storages <- L1Storages.make[IO](sdk, method).asResource
      validators = Validators.make[IO](sdk, storages)
      p2pClient = L1P2PClient.make(sdk)
      services = L1Services.make[IO](sdk, method, p2pClient, storages, validators)
      programs = L1Programs.make[IO](sdk, p2pClient, storages)
      healthChecks <- L1HealthChecks
        .make[IO](
          sdk,
          storages,
          services,
          programs,
          p2pClient,
          method
        )
        .asResource

      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        blockRumorHandler(queues.peerBlock)

      _ <- Daemons
        .start(storages, services, healthChecks)
        .asResource

      appContext <- AppContext.make[IO](cfg).asResource

      api = L1HttpApi
        .make[IO](
          L1HttpParams[IO](
            storages,
            queues,
            keyPair,
            services,
            programs,
            healthChecks,
            sdk.nodeId,
            BuildInfo.version,
            cfg.http,
            p2pClient,
            appContext
          )
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)
//      t: (Ω, BlockConsensusContext[IO]) => Cell[IO, StackF, Ω, Either[CellError, Ω], CoalgebraCommand] = BlockConsensusCell.mkCell[IO](_, _)
      stateChannel <- L1
        .make[IO](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          storages,
          validators,
          BlockConsensusCell,
          List.empty
        )
        .asResource

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )
      _ <- {
        method match {
          case cfg: RunInitialValidator =>
            gossipDaemon.startAsInitialValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              services.cluster.createSession >>
              services.session.createSession >>
              storages.node.tryModifyState(SessionStarted, NodeState.Ready)

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
          case _ => IO.pure(())
        }
      }.asResource
      _ <- stateChannel.runtime.compile.drain.handleErrorWith { error =>
        logger.error(error)("An error occurred during state channel runtime") >> error.raiseError[IO, Unit]
      }.background
    } yield
      L1Internals(
        p2pClient = p2pClient,
        queues = queues,
        storages = storages,
//        validators = validators,
        services = services,
        programs = programs,
        healthChecks = healthChecks,
        api = api,
        gossipDaemon = gossipDaemon,
        sdk = sdk,
        stateChannel = Some(stateChannel)
      )
  }
}
