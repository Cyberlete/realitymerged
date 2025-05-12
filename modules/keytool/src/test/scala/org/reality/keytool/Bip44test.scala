package org.reality.keytool

import cats.effect.IO

import scala.util.Try

import org.reality.keytool.BIP44
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signing

import org.bitcoinj.core.Sha256Hash
import org.web3j.crypto.Hash
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BIP44Test extends SimpleIOSuite with Checkers {
  val seedCode = "yard impulse luxury drive today throw farm pepper survey wreck glass federal"
  val msg = "Message for signing".getBytes()
  val msgKeccak = Hash.sha3(msg) // Note: Hash.sha3 needed for bitcoinj

  test("BIP44 wallet keys should validate for NET signature scheme") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      for {
        bip44 <- IO(new BIP44(seedCode))
        netSign <- bip44.signData(msg)
        isValid <- Signing.verifySignature(msg, netSign)(bip44.getChildKeyPairOfDepth().getPublic)
      } yield expect(isValid)
    }
  }

  test("BTC signature scheme should work for NET") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      for {
        bip44 <- IO(new BIP44(seedCode))
        netSign <- bip44.signData(msgKeccak)
        isValid <- Signing.verifySignature(msgKeccak, netSign)(bip44.getChildKeyPairOfDepth().getPublic)
      } yield expect(isValid)
    }
  }

  test("NET signature scheme should not work for BTC") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      for {
        bip44 <- IO(new BIP44(seedCode))
        isBTCValid <- IO(Try {
          val childKeyObj = bip44.getDeterministicKeyOfDepth()
          val btcJSignature = childKeyObj.sign(Sha256Hash.wrap(msg))
          childKeyObj.verify(Sha256Hash.wrap(msg), btcJSignature)
        })
      } yield expect(isBTCValid.isFailure)
    }
  }
}
