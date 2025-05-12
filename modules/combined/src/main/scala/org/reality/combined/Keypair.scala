package org.reality.combined

import java.security.MessageDigest

import cats.effect.IO

import org.reality.combined.models.{RealityKeyPair, TransactionResponse, WasmRequest}
import org.reality.keytool.KeyPairGenerator
import org.reality.security.SecurityProvider

class RealityClient(hostUrl: String)(implicit sp: SecurityProvider[IO]) {
  def getHost: String = hostUrl

  def generateKeyPair(): IO[RealityKeyPair] =
    KeyPairGenerator.makeKeyPair[IO].map { keyPair =>
      RealityKeyPair(
        publicKey = keyPair.getPublic.toString,
        privateKey = keyPair.getPrivate.toString,
        keyPair = keyPair
      )
    }

  def sendExecuteWasmRequest(request: WasmRequest): IO[TransactionResponse] = {
    val dataToHash = s"${request.functionName}:${request.params}:${request.address}:${request.fee}"
    val hash = createHash(dataToHash)

    IO.pure(
      TransactionResponse(
        status = "success",
        txHash = java.util.UUID.randomUUID().toString,
        timestamp = System.currentTimeMillis(),
        dataHash = hash
      )
    )
  }

  private def createHash(data: String): String =
    MessageDigest
      .getInstance("SHA-256", sp.provider)
      .digest(data.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
}
