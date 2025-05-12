package org.reality.domain.cell

import cats.effect.IO
import cats.effect.std.Queue
import org.reality.net.domain.block.generators._
import org.reality.kernel.Cell
import org.reality.security.signature.Signed

import eu.timepit.refined.auto._
import org.reality.dag.domain.block.NETBlock
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object L0CellSuite extends SimpleMutableIOSuite with Checkers {

  def mkL0CellMk(queue: Queue[IO, Signed[NETBlock]]) =
    L0Cell.mkL0Cell[IO](queue, null)

  test("pass net block to the queue") { _ =>
    forall(signedNETBlockGen) { netBlock =>
      for {
        netBlockQueue <- Queue.unbounded[IO, Signed[NETBlock]]
        mkNetCell = mkL0CellMk(netBlockQueue)
        cell = mkNetCell(L0CellInput.HandleNETL1(netBlock))
        res <- cell.run()
        sentData <- netBlockQueue.tryTake
      } yield expect.same((res, sentData.get), (Right(Cell.NullTerminal), netBlock))
    }
  }
}
