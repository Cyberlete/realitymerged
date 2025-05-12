package org.reality.modules

import java.security.KeyPair

import cats.Monoid
import cats.effect.Async
import cats.syntax.semigroupk._

import org.reality.domain.cell.L0Cell
import org.reality.http.p2p.{L0P2PClient, P2PClient}
import org.reality.http.routes._
import org.reality.kernel._
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.AppEnvironment.{Dev, Mainnet, Testnet}
import org.reality.sdk.config.types.HttpConfig
import org.reality.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.reality.sdk.http.routes
import org.reality.sdk.http.routes._
import org.reality.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider

import org.http4s.dsl.Http4sDsl
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

abstract class AdditionalRoutes[F[_]: Async: SecurityProvider] extends Http4sDsl[F] {

  val publicRoutes: HttpRoutes[F] // = HttpRoutes[F]..notFound[F]

  val p2pRoutes: HttpRoutes[F] // = HttpApp.notFound[F]
}
trait MakeAdditionalRoutes {
  def mkRoutes[F[_]: Async: SecurityProvider](implicit nodeApi: HttpApi[F]): AdditionalRoutes[F]
}

abstract class HttpApi[F[_]: Async: SecurityProvider](
  val storages: Storages[F],
  val queues: Queues[F],
  val services: Services[F],
  val programs: Programs[F],
  val healthchecks: HealthChecks[F],
  val key: KeyPair,
  val selfId: PeerId,
  val nodeVersion: String,
  val httpCfg: HttpConfig,
  val coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil // todo make case class with all routes below
) {
  implicit val httpApi = this
  val initializedRoutes: Seq[AdditionalRoutes[F]] = coCellRoutes.map(_(httpApi))
  val publicApp: HttpApp[F]
  val p2pApp: HttpApp[F]
  val cliApp: HttpApp[F]
  val p2pClient: P2PClient[F] // todo make trait?
  val mkCell: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]]
}

class L0HttpApi[F[_]: Async: SecurityProvider: Metrics](
  storages: L0Storages[F],
  queues: L0Queues[F],
  services: L0Services[F],
  programs: L0Programs[F],
  healthchecks: L0HealthChecks[F],
  privateKey: KeyPair,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig,
  coCellRoutes: List[(HttpApi[F]) => AdditionalRoutes[F]] = Nil,
  val p2pClient: L0P2PClient[F],
  override val mkCell: (Ω) => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]]
) extends HttpApi[F](
      storages,
      queues,
      services,
      programs,
      healthchecks,
      privateKey,
      selfId,
      nodeVersion,
      httpCfg,
      coCellRoutes
    ) {

  //  override val mkCell: Ω => Cell = mkCellObj(_)(_)
  private val mkDagCell: L0Cell.Mk[F] = L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput)

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val stateChannelRoutes: StateChannelRoutes[F] = StateChannelRoutes[F](services.stateChannel)
  private val globalSnapshotRoutes = GlobalSnapshotRoutes[F](storages.globalSnapshot, storages.cluster)
  val test: Monoid[Cell[F, StackF, Ω, Ω, Either[CellError, Ω]]] = Cell.cellMonoid[F, StackF]

  private val dagRoutes = NetRoutes[F](services.dag, mkDagCell, test) // todo put these in L0 Cell
  private val consensusInfoRoutes = new ConsensusInfoRoutes[F, SnapshotOrdinal](services.cluster, services.consensus.storage, selfId)
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  val test2: HttpRoutes[F] = consensusInfoRoutes.publicRoutes
  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val debugRoutes: HttpRoutes[F] = DebugRoutes[F](storages, services).routes

  private val metricRoutes = routes.MetricRoutes[F]().routes
  private val targetRoutes = routes.TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey.getPrivate, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty[F]) <+>
              metricRoutes <+>
              targetRoutes <+>
              (if (environment == Mainnet && coCellRoutes.nonEmpty) HttpRoutes.empty else stateChannelRoutes.publicRoutes) <+>
              clusterRoutes.publicRoutes <+>
              globalSnapshotRoutes.publicRoutes <+>
              dagRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes <+>
              initializedRoutes.map(_.publicRoutes).fold(consensusInfoRoutes.publicRoutes)(_ <+> _)
          }
        }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey.getPrivate, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            PeerAuthMiddleware.requestCollateralVerifierMiddleware(services.collateral)(
              clusterRoutes.p2pRoutes <+>
                nodeRoutes.p2pRoutes <+>
                gossipRoutes.p2pRoutes <+>
                globalSnapshotRoutes.p2pRoutes <+>
                healthcheckP2PRoutes <+>
                initializedRoutes.map(_.p2pRoutes).fold(consensusRoutes)(_ <+> _)
            )
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
