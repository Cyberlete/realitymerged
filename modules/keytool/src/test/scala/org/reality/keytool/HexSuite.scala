package org.reality.keytool

import cats.effect.{IO, Resource}
import cats.implicits.toFoldableOps
import cats.syntax.traverse._

import org.reality.security.SecurityProvider
import org.reality.security.key.ops.PrivateKeyOps

import eu.timepit.refined.types.numeric.NonNegInt
import weaver.{Expectations, MutableIOSuite}

object HexSuite extends MutableIOSuite {
  override type Res = SecurityProvider[IO]

  override def sharedResource: Resource[IO, Res] = SecurityProvider.forAsync[IO]

  def testKeyRecreationNTimes(n: NonNegInt)(implicit sp: SecurityProvider[IO]): IO[Expectations] = {
    def testKey(implicit sp: SecurityProvider[IO]): IO[Expectations] =
      for {
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        privateHex = keyPair.getPrivate.toHex
        recreatedKeyPair <- privateHex.toKeyPairFromPrivate[IO]
        result = expect.eql(
          (keyPair.getPublic.getEncoded.toList, keyPair.getPrivate.getEncoded.toList),
          (recreatedKeyPair.getPublic.getEncoded.toList, recreatedKeyPair.getPrivate.getEncoded.toList)
        )
      } yield result

    List.fill(n.value)(testKey).sequence.map(_.combineAll)
  }

  test("keypair recreated from private key hex representation should be exactly the same") { implicit sp =>
    testKeyRecreationNTimes(NonNegInt(1000))
  }
}
