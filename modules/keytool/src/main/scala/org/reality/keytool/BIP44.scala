package org.reality.keytool

import java.math.BigInteger
import java.security.{SecureRandom, _}
import java.time.Instant
import java.util

import cats.effect.Async

import org.reality.keytool.BIP44.{getPrivateKeyFromECBigIntAndCurve, getPublicKeyFromECPoint}
import org.reality.keytool.KeyStoreUtils.insertProvider
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signing

import org.bitcoinj.crypto.{ChildNumber, DeterministicKey, HDPath}
import org.bitcoinj.wallet.{DeterministicKeyChain, DeterministicSeed}
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.jce.spec._
import org.bouncycastle.math.ec

class BIP44(
  seedPhrase: String,
  childIndex: Int = 0,
  passphrase: String = "",
  creationTime: Long = Instant.now().getEpochSecond
) {
  // 693 is NET coin type taken from https://github.com/satoshilabs/slips/blob/master/slip-0044.md
  private val chainPathPrefix: String = "M/44H/693H/0H/0/" // todo new prefix for Reality
  private val seed = new DeterministicSeed(seedPhrase, null, passphrase, creationTime)
  val chain: DeterministicKeyChain = DeterministicKeyChain.builder.seed(seed).build

  def signData[F[_]: Async: SecurityProvider](data: Array[Byte])(
    implicit privateKey: PrivateKey = getChildKeyPairOfDepth().getPrivate
  ): F[Array[Byte]] =
    Signing.signData(data)(privateKey)

  def getDeterministicKeyOfDepth(depth: Int = childIndex): DeterministicKey = {
    val chainPath: String = chainPathPrefix + depth.toString
    val keyPath: util.List[ChildNumber] = HDPath.parsePath(chainPath)
    chain.getKeyByPath(keyPath, true)
  }

  def getChildKeyPairOfDepth(depth: Int = childIndex): KeyPair = {
    val key: DeterministicKey = getDeterministicKeyOfDepth(depth)
    val rawPrivate: BigInteger = key.getPrivKey
    val privateKey: PrivateKey = getPrivateKeyFromECBigIntAndCurve(rawPrivate, "secp256k1")
    val publicKey: PublicKey = getPublicKeyFromECPoint(key.getPubKeyPoint)
    new KeyPair(publicKey, privateKey)
  }
}

object BIP44 extends App {
  import org.bouncycastle.jce.ECNamedCurveTable

  def getPrivateKeyFromECBigIntAndCurve(s: BigInteger, curveName: String) = {
    val ecParameterSpec: ECParameterSpec = ECNamedCurveTable.getParameterSpec(curveName)
    val privateKeySpec: ECPrivateKeySpec = new ECPrivateKeySpec(s, ecParameterSpec)
    val keyFactory = KeyFactory.getInstance("ECDSA", insertProvider())
    val priv = keyFactory.generatePrivate(privateKeySpec)
    priv
  }

  def getPublicKeyFromECPoint(point: ec.ECPoint) = {
    val kf = KeyFactory.getInstance("ECDSA", insertProvider())
    val curve: X9ECParameters = SECNamedCurves.getByName("secp256k1")
    val params = new ECParameterSpec(curve.getCurve, curve.getG, curve.getN, curve.getH)
    kf.generatePublic(new ECPublicKeySpec(point, params))
  }

  def generateDeterministicSeed(passphrase: String): DeterministicSeed = {
    val random = new SecureRandom()
    new DeterministicSeed(random, DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS, passphrase)
  }
}
