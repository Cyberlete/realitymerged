package org.reality.sdk.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.semigroupk._

import scala.reflect.runtime.universe.TypeTag

import org.reality.sdk.domain.consensus.ConsensusFunctions
import org.reality.sdk.infrastructure.consensus.declaration._
import org.reality.sdk.infrastructure.consensus.message._
import org.reality.sdk.infrastructure.gossip.RumorHandler
import org.reality.security.SecurityProvider

import io.circe.Decoder

object ConsensusHandler {

  def make[F[
    _
  ]: Async: SecurityProvider, Event: TypeTag: Decoder, Key: Show: TypeTag: Decoder, Artifact: TypeTag: Decoder, Context](
    storage: ConsensusStorage[F, Event, Key, Artifact, Context],
    manager: ConsensusManager[F, Key, Artifact, Context],
    fns: ConsensusFunctions[F, Event, Key, Artifact, Context]
  ): RumorHandler[F] = {

    val eventHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusEvent[Event]]() { rumor =>
      if (fns.triggerPredicate(rumor.content.value))
        storage.addTriggerEvent(rumor.origin, (rumor.ordinal, rumor.content.value)) >>
          manager.facilitateOnEvent
      else
        storage.addEvent(rumor.origin, (rumor.ordinal, rumor.content.value))
    }

    val facilityHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Facility]]() { rumor =>
        // TODO: there isn't (or I didn't notice it yet) any mechanism preventing spamming of messages into consensus resources.
        // Consensus resources are stored in memory so if the protection is not in place we could attack a network
        // by sending messages for future rounds
        storage.addFacility(rumor.origin, rumor.content.key, rumor.content.declaration) >>
          manager.requestStateUpdate() >>
          manager.followConsensus
      }

    val proposalHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Proposal]]() { rumor =>
      storage.addProposal(rumor.origin, rumor.content.key, rumor.content.declaration) >>
        manager.requestStateUpdate()
    }

    val artifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact) >>
        manager.requestStateUpdate()
    }

    val signatureHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, MajoritySignature]]() { rumor =>
        storage.addSignature(rumor.origin, rumor.content.key, rumor.content.declaration) >>
          manager.requestStateUpdate()
      }

    val withdrawPeerDeclarationHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusWithdrawPeerDeclaration[Key]]() { rumor =>
        storage.addWithdrawPeerDeclaration(rumor.origin, rumor.content.key, rumor.content.kind) >>
          manager.requestStateUpdate()
      }

    eventHandler <+>
      facilityHandler <+>
      proposalHandler <+>
      signatureHandler <+>
      artifactHandler <+>
      withdrawPeerDeclarationHandler
  }

}
