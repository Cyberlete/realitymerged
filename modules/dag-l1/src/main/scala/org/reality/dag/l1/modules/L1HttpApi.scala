package org.reality.dag.l1.modules

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}
import cats.effect.{Async, IO, Resource}
import cats.implicits._

import org.reality.BuildInfo
import org.reality.dag.l1.app.AppContext
import org.reality.dag.l1.cli.method.{RunInitialValidator, RunValidator}
import org.reality.dag.l1.http.Routes
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.reality.domain.cell.AlgebraCommand
import org.reality.domain.cell.AlgebraCommand.{EnqueueNETL1Data, EnqueueStateChannelSnapshot}
import org.reality.domain.cell.CoalgebraCommand.{Empty, ProcessNETL1, ProcessStateChannelSnapshot}
import org.reality.domain.cell.L0Cell.Coalgebra
import org.reality.domain.cell.L0CellInput.{HandleNETL1, HandleStateChannelSnapshot}
import org.reality.ext.cats.effect.ResourceIO
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel._
import org.reality.modules.{AdditionalRoutes, HTTPParams, HttpApi}
import org.reality.schema.node.NodeState
import org.reality.schema.node.NodeState.SessionStarted
import org.reality.schema.peer.PeerId
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.config.types.HttpConfig
import org.reality.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.reality.sdk.http.routes._
import org.reality.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.reality.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.resources.MkHttpServer
import org.reality.sdk.resources.MkHttpServer.ServerName
import org.reality.security.SecurityProvider

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object EmptyCellObj {
  def mkCell[F[_]: Async]: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data =>
    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueStateChannelSnapshot(snapshot) =>
                //              Algebra.enqueueStateChannelSnapshot(stateChannelOutputQueue)(snapshot)
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
              case EnqueueNETL1Data(data) =>
                //              Algebra.enqueueDAGL1Data(Queue[F, Signed[DAGBlock]])(data)
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
              case _ =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
          case ProcessNETL1(data)                    => Coalgebra.processNETL1(data)
          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          case _                                     => Coalgebra.empty()
        }
      ),
      {
        case HandleNETL1(data)                    => ProcessNETL1(data)
        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
        case _                                    => Empty() // todo delete this
      }
    )
}
case class L1HttpParams[F[_]: Async: SecurityProvider: Metrics](
  storages: L1Storages[F],
  queues: L1Queues[F],
  keyPair: KeyPair,
  services: L1Services[F],
  programs: L1Programs[F],
  healthchecks: L1HealthChecks[F],
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig,
  p2pClient: L1P2PClient[F],
  appContext: AppContext[F],
  coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil
) extends HTTPParams[F](
      storages,
      queues,
      services,
      programs,
      healthchecks,
      keyPair,
      selfId: PeerId,
      nodeVersion,
      httpCfg,
      coCellRoutes,
      p2pClient
    )

object L1HttpApi {

  def mkResourcesHack[A <: CliMethod](method: A, sdk: SDK[IO]): Resource[IO, (L1HttpApi[IO], Validators[IO])] = { // todo unify signature with L0 ala MkHttpApi
    import sdk._
    val cfg = method.appConfig
    println(
      "cfg - L1HttpApi - cfg.http.publicHttp - " + cfg.http.publicHttp + "-cfg.http.p2pHttp - " + cfg.http.p2pHttp + "-cfg.http.cliHttp - " + cfg.http.cliHttp
    )
    println("cfg - L1HttpApi - " + cfg.toString)
    for {
      queues <- L1Queues.make[IO](sdk).asResource
      storages <- L1Storages.make(sdk, method).asResource
      p2pClient: L1P2PClient[IO] = L1P2PClient.make(sdk)
      validators = Validators.make[IO](sdk, storages)
      services = L1Services.make[IO](sdk, method, p2pClient, storages, validators)
      programs = L1Programs.make[IO](sdk, p2pClient, storages)
      healthChecks <- L1HealthChecks.make[IO](sdk, storages, services, programs, p2pClient, method).asResource
      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+> blockRumorHandler(
        queues.peerBlock
      )
      _ <- Daemons.start(storages, services, healthChecks).asResource
      appContext <- AppContext.make[IO].asResource
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
      api: L1HttpApi[IO] = L1HttpApi.make[IO](
        L1HttpParams(
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

      _ <- MkHttpServer[IO].newEmber(ServerName("l1public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("l1p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("l1cli"), cfg.http.cliHttp, api.cliApp)
    } yield (api, validators)
  }

  def mkResources[A <: CliMethod](method: A, sdk: SDK[IO]): Resource[IO, L1HttpApi[IO]] = { // todo unify signature with L0 ala MkHttpApi
    import sdk._
    val cfg = method.appConfig
    println(
      "cfg - L1HttpApi - cfg.http.publicHttp - " + cfg.http.publicHttp + "-cfg.http.p2pHttp - " + cfg.http.p2pHttp + "-cfg.http.cliHttp - " + cfg.http.cliHttp
    )
    println("cfg - L1HttpApi - " + cfg.toString)
    for {
      queues <- L1Queues.make[IO](sdk).asResource
      storages <- L1Storages.make(sdk, method).asResource
      p2pClient: L1P2PClient[IO] = L1P2PClient.make(sdk)
      validators = Validators.make[IO](sdk, storages)
      services = L1Services.make[IO](sdk, method, p2pClient, storages, validators)
      programs = L1Programs.make[IO](sdk, p2pClient, storages)
      healthChecks <- L1HealthChecks.make[IO](sdk, storages, services, programs, p2pClient, method).asResource
      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+> blockRumorHandler(
        queues.peerBlock
      )
      _ <- Daemons.start(storages, services, healthChecks).asResource
      appContext <- AppContext.make[IO].asResource
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
      api: L1HttpApi[IO] = L1HttpApi.make[IO](
        L1HttpParams(
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

      _ <- MkHttpServer[IO].newEmber(ServerName("l1public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("l1p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("l1cli"), cfg.http.cliHttp, api.cliApp)
    } yield api
  }

  def make[F[_]: Async: Random: SecurityProvider: Metrics: Supervisor](params: L1HttpParams[F]): L1HttpApi[F] =
    new L1HttpApi[F](
      params.storages,
      params.queues,
      params.keyPair,
      params.services,
      params.programs,
      params.healthchecks,
      params.selfId,
      params.nodeVersion,
      params.httpCfg,
      params.p2pClient,
      params.appContext,
      EmptyCellObj.mkCell, // todo make default, L1 cell in statechannel
      params.coCellRoutes
    )
}

case class L1HttpApi[F[_]: Async: Random: SecurityProvider: Metrics: Supervisor](
  override val storages: L1Storages[F],
  override val queues: L1Queues[F],
  keyPair: KeyPair,
  override val services: L1Services[F],
  override val programs: L1Programs[F],
  override val healthchecks: L1HealthChecks[F],
  override val selfId: PeerId,
  override val nodeVersion: String,
  override val httpCfg: HttpConfig,
  override val p2pClient: L1P2PClient[F],
  appContext: AppContext[F],
  override val mkCell: (Ω) => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]],
  override val coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil
) extends HttpApi[F](storages, queues, services, programs, healthchecks, keyPair, selfId, nodeVersion, httpCfg, coCellRoutes) {
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val dagRoutes =
    Routes[F](
      services.transaction,
      storages.transaction,
      storages.l0Cluster,
      queues.peerBlockConsensusInput,
      p2pClient,
      appContext,
      httpCfg,
      selfId,
      keyPair
    )
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)
  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val metricRoutes = MetricRoutes[F]().routes
  private val targetRoutes = TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      `X-Id-Middleware`.responseMiddleware(selfId) {
        targetRoutes <+>
          clusterRoutes.publicRoutes <+>
          nodeRoutes.publicRoutes <+>
          metricRoutes <+> initializedRoutes.map(_.publicRoutes).fold(dagRoutes.publicRoutes)(_ <+> _)
      }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(keyPair.getPrivate, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            clusterRoutes.p2pRoutes <+>
              nodeRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              healthcheckP2PRoutes <+>
              initializedRoutes.map(_.p2pRoutes).fold(dagRoutes.p2pRoutes)(_ <+> _)
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes <+> dagRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
