package org.reality.rosetta.domain.construction

import cats.effect.{IO, Resource}

import org.reality.rosetta.domain.error.InvalidPublicKey
import org.reality.rosetta.domain.{AccountIdentifier, CurveType, RosettaPublicKey}
import org.reality.schema.address.Address
import org.reality.security.SecurityProvider
import org.reality.security.hex.Hex

import eu.timepit.refined.auto._
import weaver._
import weaver.scalacheck.Checkers

object ConstructionServiceSuite extends MutableIOSuite with Checkers {

  type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, Res] = SecurityProvider.forAsync[IO]

  test("derives public key") { implicit res =>
    val publicKey = RosettaPublicKey(
      Hex(
        "0483e4f38072fa59975fc796f220f4c07a7a6a3af1ad7fc091cbd6b8ebe78bac6a959da3587e6e761daf93693d4d2dc6b349fbc44dac5a9fcc5f809a59e93818ea"
      ),
      CurveType.SECP256K1
    )

    val expected = AccountIdentifier(Address("NET8Q4CnZ1fSMn1Hrui9MmPogEp5UoT5MSH1LwHg"), None)

    val cs = ConstructionService.make[IO]()

    cs.derive(publicKey).rethrowT.map {
      expect.eql(expected, _)
    }
  }

  test("returns InvalidPublicKey when cannot derive public key") { implicit res =>
    val publicKey = RosettaPublicKey(Hex("foobarbaz"), CurveType.SECP256K1)

    val cs = ConstructionService.make[IO]()

    cs.derive(publicKey).value.map {
      expect.eql(Left(InvalidPublicKey), _)
    }
  }
}
