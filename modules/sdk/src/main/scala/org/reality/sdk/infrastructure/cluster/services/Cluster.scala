package org.reality.sdk.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import org.reality.ext.crypto._
import org.reality.schema.cluster._
import org.reality.schema.node.NodeState
import org.reality.schema.peer._
import org.reality.sdk.config.types.HttpConfig
import org.reality.sdk.domain.cluster.services.Cluster
import org.reality.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.reality.sdk.domain.node.NodeStorage
import org.reality.security.SecurityProvider
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed

import fs2.concurrent.SignallingRef

object Cluster {

  def make[F[_]: Async: SecurityProvider](
    leavingDelay: FiniteDuration,
    httpConfig: HttpConfig,
    selfId: PeerId,
    keyPair: KeyPair,
    clusterStorage: ClusterStorage[F],
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F],
    seedlist: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash
  ): Cluster[F] =
    new Cluster[F] {

      def getRegistrationRequest: F[RegistrationRequest] =
        for {
          session <- sessionStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[SessionToken](SessionDoesNotExist)
          }
          clusterSession <- clusterStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[ClusterSessionToken](ClusterSessionDoesNotExist)
          }
          clusterId = clusterStorage.getClusterId
          state <- nodeStorage.getNodeState
          seedlistHash <- seedlist.hashF
        } yield
          RegistrationRequest(
            selfId,
            httpConfig.externalIp,
            httpConfig.publicHttp.port,
            httpConfig.p2pHttp.port,
            session,
            clusterSession,
            clusterId,
            state,
            seedlistHash,
            versionHash
          )

      def signRequest(signRequest: SignRequest): F[Signed[SignRequest]] =
        signRequest.sign(keyPair)

      def leave(): F[Unit] = {
        def process =
          nodeStorage.setNodeState(NodeState.Leaving) >>
            Temporal[F].sleep(leavingDelay) >>
            nodeStorage.setNodeState(NodeState.Offline) >>
            Temporal[F].sleep(5.seconds) >>
            restartSignal.set(())

        Temporal[F].start(process).void
      }

      def info: F[Set[PeerInfo]] =
        getRegistrationRequest.flatMap { req =>
          def self = PeerInfo(
            req.id,
            req.ip,
            req.publicPort,
            req.p2pPort,
            req.session.value.toString,
            req.state
          )

          clusterStorage.getResponsivePeers.map(_.map(PeerInfo.fromPeer) + self)
        }

      def createSession: F[ClusterSessionToken] =
        clusterStorage.createToken

    }

}
