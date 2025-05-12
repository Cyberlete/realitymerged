package org.reality.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.kernel.{Next, PartialPrevious}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, Order, Show}

import scala.collection.immutable.SortedSet
import scala.reflect.runtime.universe.TypeTag

import org.reality.dag.domain.block.NETBlock
import org.reality.schema.KeyIndex
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.types.ConsensusConfig
import org.reality.sdk.domain.cluster.services.Session
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.domain.gossip.Gossip
import org.reality.sdk.domain.node.NodeStorage
import org.reality.sdk.domain.trust.storage.TrustStorage
import org.reality.sdk.infrastructure.gossip.RumorHandler
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.security.SecurityProvider

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client

object Consensus {

  def make[
    F[_]: Async: Supervisor: Random: SecurityProvider: Metrics,
    Event: TypeTag: Decoder,
    Key: Show: Order: Ordering: Next: KeyIndex: TypeTag: Encoder: Decoder: PartialPrevious,
    Artifact <: AnyRef: Show: Eq: TypeTag: Encoder: Decoder,
    Context <: AnyRef: Show: Eq: TypeTag: Encoder: Decoder
  ](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    consensusConfig: ConsensusConfig,
    seedlist: Option[Set[PeerId]],
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    client: Client[F],
    session: Session[F],
    trustStorage: TrustStorage[F],
    blockExtractor: Artifact => SortedSet[NETBlock]
  ): F[Consensus[F, Event, Key, Artifact, Context]] =
    for {
      storage <- ConsensusStorage.make[F, Event, Key, Artifact, Context](selfId)
      stateUpdater = ConsensusStateUpdater.make[F, Event, Key, Artifact, Context](
        consensusFns,
        storage,
        gossip,
        keyPair,
        selfId,
        trustStorage,
        blockExtractor
      )
      stateCreator = ConsensusStateCreator.make[F, Event, Key, Artifact, Context](
        consensusFns,
        storage,
        trustStorage,
        gossip,
        selfId,
        keyPair,
        seedlist
      )
      stateRemover = ConsensusStateRemover.make[F, Event, Key, Artifact, Context](
        storage,
        gossip
      )
      consClient = ConsensusClient.make[F, Key, Artifact, Context](client, session)
      manager <- ConsensusManager.make[F, Event, Key, Artifact, Context](
        consensusConfig,
        consensusFns,
        storage,
        stateCreator,
        stateUpdater,
        stateRemover,
        nodeStorage,
        clusterStorage,
        consClient,
        selfId
      )
      handler = ConsensusHandler.make[F, Event, Key, Artifact, Context](storage, manager, consensusFns)
      routes = new ConsensusRoutes[F, Key, Artifact, Context](storage)
    } yield new Consensus(handler, storage, manager, routes)
}

sealed class Consensus[F[_]: Async, Event, Key, Artifact, Context] private (
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact, Context],
  val manager: ConsensusManager[F, Key, Artifact, Context],
  val routes: ConsensusRoutes[F, Key, Artifact, Context]
) {}
