package org.reality.ext.http4s

import org.reality.schema.address.{Address, NETAddressRefined}

import eu.timepit.refined.refineV

object AddressVar {
  def unapply(str: String): Option[Address] = refineV[NETAddressRefined](str).toOption.map(Address(_))
}
