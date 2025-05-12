package org.reality.combined.examples

import cats.effect.IO
import cats.implicits.toTraverseOps

import org.reality.combined.{CoCell, DataHashCoCell, Portal}

class CombinedDataHashExample extends Portal {
  import CoCell._

  private val l0CoCell: CombinedL0 = CombinedL0()
  private val stateChannelCoCell: DataHashCoCell = DataHashCoCell()

  val mergedCells: Seq[CoCell] = l0CoCell ++ stateChannelCoCell

  val cellProgram: List[String] => IO[Seq[Context[CoCell]]] = (args: List[String]) =>
    for {
      coCellsInContext <- mergedCells.map(_.setup(args)).sequence
    } yield coCellsInContext

}
