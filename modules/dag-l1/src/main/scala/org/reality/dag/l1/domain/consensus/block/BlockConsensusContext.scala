package org.reality.dag.l1.domain.consensus.block

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.reality.dag.block.BlockValidator
import org.reality.dag.l1.domain.block.BlockStorage
import org.reality.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.reality.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.reality.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.reality.dag.l1.domain.transaction.TransactionStorage
import org.reality.dag.transaction.TransactionValidator
import org.reality.schema.peer.PeerId
import org.reality.sdk.domain.cluster.storage.ClusterStorage
import org.reality.security.SecurityProvider

case class BlockConsensusContext[F[_]: Async: SecurityProvider: Random](
  blockConsensusClient: BlockConsensusClient[F],
  blockStorage: BlockStorage[F],
  blockValidator: BlockValidator[F],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F],
  keyPair: KeyPair,
  selfId: PeerId,
  transactionStorage: TransactionStorage[F],
  transactionValidator: TransactionValidator[F]
)
