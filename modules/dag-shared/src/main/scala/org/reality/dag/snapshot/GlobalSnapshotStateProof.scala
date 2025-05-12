package org.reality.dag.snapshot

import org.reality.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProof(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  deployAppTransactionsInfoProof: Hash,
  registerAppProviderTransactionsInfoProof: Hash,
  candidatesProof: Hash,
  nextRotationFacilitatorsProof: Hash
)
