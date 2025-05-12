package org.reality.combined

import derevo.circe.magnolia.encoder
import derevo.derive

@derive(encoder)
case class AddressBalance(balance: Long, ordinal: Long)
