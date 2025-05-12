package org.reality.sdk.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.show._

import org.reality.cli.env.{KeyAlias, Password, StorePath}
import org.reality.ext.cats.effect._
import org.reality.ext.crypto._
import org.reality.keytool.KeyStoreUtils
import org.reality.schema.cluster.ClusterId
import org.reality.schema.generation.Generation
import org.reality.schema.peer.PeerId
import org.reality.sdk.cli.CliMethod
import org.reality.sdk.http.p2p.SdkP2PClient
import org.reality.sdk.infrastructure.cluster.services.Session
import org.reality.sdk.infrastructure.logs.LoggerConfigurator
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.seedlist.{Loader => SeedlistLoader}
import org.reality.sdk.modules._
import org.reality.sdk.resources.SdkResources
import org.reality.security.SecurityProvider

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object RealityBootstrap {
  private def loadKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password
  ): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        keyStore.value.toString,
        alias.value.value,
        password.value.value.toCharArray,
        password.value.value.toCharArray
      )

  protected val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def commonBootstrap[A <: CliMethod](
    name: String,
    header: String,
    clusterId: ClusterId,
    helpFlag: Boolean = true,
    version: String = "",
    method: A,
    run: (A, SDK[IO]) => Resource[IO, NodeInternals]
  ): IO[(Resource[IO, NodeInternals], SignallingRef[IO, Unit])] = {
    val cfg = method.sdkConfig

    val keyStore = method.keyStore
    val alias = method.alias
    val password = method.password

    LoggerConfigurator.configureLogger[IO](cfg.environment) >>
      logger.info(s"App environment: ${cfg.environment}") >>
      logger.info(s"App version: ${version.show}") >>
      Random.scalaUtilRandom[IO].flatMap { _random =>
        SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
          loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
            val selfId = PeerId.fromPublic(_keyPair.getPublic)
            Metrics.forAsync[IO](Seq(("application", name))).use { implicit _metrics =>
              SignallingRef.of[IO, Unit](()).flatMap { _restartSignal =>
                def mkSDK =
                  Supervisor[IO].flatMap { implicit _supervisor =>
                    for {
                      _ <- IO(System.setProperty("self_id", selfId.show)).asResource
                      _ <- logger.info(s"Self peerId: ${selfId}").asResource
                      _generation <- Generation.make[IO].asResource
                      versionHash <- version.hash.liftTo[IO].asResource
                      _seedlist <- method.seedlistPath
                        .fold(none[Set[PeerId]].pure[IO])(SeedlistLoader.make[IO].load(_).map(_.some))
                        .asResource
                      _ <- _seedlist
                        .map(_.size)
                        .fold(logger.info(s"Seedlist disabled.")) { size =>
                          logger.info(s"Seedlist enabled. Allowed nodes: $size")
                        }
                        .asResource
                      storages <- SdkStorages.make[IO](clusterId, cfg).asResource
                      res <- SdkResources.make[IO](cfg, _keyPair.getPrivate, storages.session, selfId)
                      session = Session.make[IO](storages.session, storages.node, storages.cluster)
                      p2pClient = SdkP2PClient.make[IO](res.client, session)
                      queues <- SdkQueues.make[IO].asResource
                      validators = SdkValidators.make[IO](_seedlist)
                      services <- SdkServices
                        .make[IO](
                          cfg,
                          validators,
                          selfId,
                          _generation,
                          _keyPair,
                          storages,
                          queues,
                          session,
                          p2pClient.node,
                          _seedlist,
                          _restartSignal,
                          versionHash
                        )
                        .asResource

                      programs <- SdkPrograms
                        .make[IO](
                          cfg,
                          storages,
                          services,
                          p2pClient.cluster,
                          p2pClient.sign,
                          services.localHealthcheck,
                          _seedlist,
                          selfId,
                          versionHash
                        )
                        .asResource

                      sdk = new SDK[IO] {
                        val random: Random[IO] = _random
                        val securityProvider: SecurityProvider[IO] = _securityProvider
                        val metrics: Metrics[IO] = _metrics
                        val supervisor: Supervisor[IO] = _supervisor

                        val keyPair: KeyPair = _keyPair
                        val seedlist: Option[Set[PeerId]] = _seedlist
                        val generation: Generation = _generation

                        val sdkResources: SdkResources[IO] = res
                        val sdkP2PClient: SdkP2PClient[IO] = p2pClient
                        val sdkQueues: SdkQueues[IO] = queues
                        val sdkStorages: SdkStorages[IO] = storages
                        val sdkServices: SdkServices[IO] = services
                        val sdkPrograms: SdkPrograms[IO] = programs

                        val sdkValidators: SdkValidators[IO] = validators

                        def restartSignal: SignallingRef[IO, Unit] = _restartSignal
                      }
                    } yield sdk
                  }

                def startup: Resource[IO, NodeInternals] =
                  mkSDK.handleErrorWith { (e: Throwable) =>
                    (logger.error(e)(s"Unhandled exception during initialization.") >> IO
                      .raiseError[SDK[IO]](e)).asResource
                  }.flatMap { sdk =>
                    run(method, sdk).handleErrorWith { (e: Throwable) =>
                      (logger.error(e)(s"Unhandled exception during runtime.") >> IO.raiseError[NodeInternals](e)).asResource
                    }
                  }

                IO.pure((startup, _restartSignal))
              }
            }
          }
        }
      }
  }

  def bootstrap[A <: CliMethod](
    name: String,
    header: String,
    clusterId: ClusterId,
    helpFlag: Boolean = true,
    version: String = "",
    opts: Opts[A],
    run: (A, SDK[IO]) => Resource[IO, NodeInternals]
  ): Opts[IO[(Resource[IO, NodeInternals], SignallingRef[IO, Unit])]] =
    opts.map { method =>
      commonBootstrap(
        name = name,
        header = header,
        clusterId = clusterId,
        helpFlag = helpFlag,
        version = version,
        method = method,
        run = run
      )
    }
}
