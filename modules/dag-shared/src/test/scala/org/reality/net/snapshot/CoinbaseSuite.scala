package org.reality.net.snapshot

import org.reality.dag.snapshot.Coinbase
import org.reality.ext.crypto.RefinedHashableF
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends SimpleIOSuite with Checkers {

  test("coinbase hash should be constant and known") {
    Coinbase.value.hashF.map(
      expect.same(
        Coinbase.hash,
        _
      )
    )
  }
}
