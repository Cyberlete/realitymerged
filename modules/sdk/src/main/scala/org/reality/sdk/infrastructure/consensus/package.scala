package org.reality.sdk.infrastructure

import org.reality.schema.gossip.Ordinal
import org.reality.schema.peer.PeerId

package object consensus {
  type Bound = Map[PeerId, Ordinal]
}
