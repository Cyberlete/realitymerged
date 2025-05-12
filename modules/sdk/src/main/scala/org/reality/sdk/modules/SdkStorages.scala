package org.reality.sdk.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.schema.cluster.ClusterId
import org.reality.sdk.config.types.SdkConfig
import org.reality.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import org.reality.sdk.infrastructure.gossip.RumorStorage
import org.reality.sdk.infrastructure.node.NodeStorage

object SdkStorages {

  def make[F[_]: Async](
    clusterId: ClusterId,
    cfg: SdkConfig
  ): F[SdkStorages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F](clusterId)
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossipConfig.storage)
    } yield
      new SdkStorages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage
      ) {}
}

sealed abstract class SdkStorages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F]
)
