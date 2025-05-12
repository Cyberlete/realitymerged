package org.reality.schema

import org.reality.schema.address.NETAddressRefined

import eu.timepit.refined.refineV
import weaver.FunSuite
import weaver.scalacheck.Checkers

object AddressSuite extends FunSuite with Checkers {
  val validAddress = "NET2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQgech"

  test("correct NET Address should pass validation") {
    val result = refineV[NETAddressRefined].apply[String](validAddress)

    expect(result.isRight)
  }

  test("stardust collective NET Address should pass validation") {
    val result = refineV[NETAddressRefined].apply[String](StardustCollective.address)

    expect(result.isRight)
  }

  test("too long NET Address should fail validation") {
    val result = refineV[NETAddressRefined].apply[String](validAddress + "a")

    expect(result.isLeft)
  }

  test("NET Address with wrong parity should fail validation") {
    val result = refineV[NETAddressRefined].apply[String]("NET1" + validAddress.substring(4))

    expect(result.isLeft)
  }

  test("NET Address with non-base58 character should fail validation") {
    val result = refineV[NETAddressRefined].apply[String](validAddress.replace("h", "0"))

    expect(result.isLeft)
  }
}
