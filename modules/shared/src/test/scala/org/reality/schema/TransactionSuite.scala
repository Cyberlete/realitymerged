package org.reality.schema

import cats.effect.IO

import org.reality.ext.crypto._
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.hash.Hash

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TransactionSuite extends SimpleIOSuite with Checkers {

  test("Transaction's representation used for hashing should follow expected format") {
    val transaction = StandardTransaction(
      Address("NET2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
      Address("NET2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"),
      TransactionAmount(10L),
      TransactionFee(3L),
      TransactionReference(TransactionOrdinal(2L), Hash("someHash")),
      TransactionSalt(1234L)
    )

    val expectedToEncode =
      "2" +
        "40" +
        "NET2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd" +
        "40" +
        "NET2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh" +
        "1" +
        "a" +
        "8" +
        "someHash" +
        "1" +
        "2" +
        "1" +
        "3" +
        "3" +
        "4d2"

    IO.pure(expect.same(expectedToEncode, transaction.toEncode))
  }

  test("Hash for a new Transaction schema should be the same as hash for old Transaction schema") {
    val expectedHash = Hash("abef7f5aae84a524eec4a83c8185b523af8ea2946c4c36961d470f0aa46c04fc")

    val transaction = StandardTransaction(
      Address("NET53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
      Address("NET53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
      TransactionAmount(100000000L),
      TransactionFee(0L),
      TransactionReference(
        TransactionOrdinal(1L),
        Hash("d5149e2339ced3b285062dc403ba0c89642792a462476dc35f63e0328b3cac52")
      ),
      TransactionSalt(-6326757804706870905L)
    )

    transaction.hashF.map(expect.same(expectedHash, _))
  }
}
