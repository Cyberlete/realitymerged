package org.reality.sdk.domain.snapshot

import org.reality.security.signature.Signed

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Signed[Artifact],
    signedArtifact: Signed[Artifact]
  ): F[Context]
}
