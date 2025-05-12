package org.reality.cutoff

import org.reality.schema.SnapshotOrdinal

import weaver.FunSuite
import weaver.scalacheck.Checkers

object LogarithmicOrdinalCutoffSuite extends FunSuite with Checkers {
  def check(ordinal: Int, expectedContains: List[Int]) = {
    val result = LogarithmicOrdinalCutoff.cutoff(SnapshotOrdinal.unsafeFromLong(ordinal.toLong))

    expect.all(expectedContains.map(i => SnapshotOrdinal.unsafeFromLong(i.toLong)).forall(result.contains))
  }

  test("preserves last ten ordinals") {
    check(0, List(0)) &&
    check(3, List(0, 1, 2, 3)) &&
    check(21, List(12, 13, 14, 15, 16, 17, 18, 19, 20, 21)) &&
    check(291, List(282, 283, 284, 285, 286, 287, 288, 289, 290, 291))
  }

  test("preserves tenth") {
    check(26, List(0, 10)) &&
    check(82, List(0, 10, 20, 30, 40, 50, 60, 70)) &&
    check(882, List(770, 780, 790, 800, 810, 820, 830, 840, 850, 860, 870))
  }

  test("preserves hundreds") {
    check(260, List(0, 100)) &&
    check(2601, List(2490, 2500, 2510, 2520, 2530, 2540, 2550, 2560, 2570, 2580, 2590))
  }

  test("preserves thousands") {
    check(2523, List(0, 1000, 2000)) &&
    check(82314, List(72000, 73000, 74000, 75000, 76000, 77000, 78000, 79000, 80000, 81000, 82000))
  }
}
