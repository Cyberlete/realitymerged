package org.reality.combined.examples

import cats.implicits._

import org.reality.combined.CoCell.fixed2
import org.reality.combined.{CoCell, CoHomF, Portal}
import org.reality.dag.l1.domain.consensus.block.AlgebraCommand.InformAboutRoundStartFailure
import org.reality.kernel._
trait CompositeCell extends CoCell {}

class ComposeExamples extends Portal {
  import CoCell.toCoHom
  val netL0 = new NetL0 {}
  val combinedStateChannel = new NetStateChannel {}

  /** In Combine we loaded the CoCells into runtime. Here, we can do more stuff to create "first class objects" out of computation using an
    * Arrow (>>>) which chains them and returns an object which can handle input and be passed around. It also does some extra type checking
    * which we will use in making proofs.
    */
  val computationObject: Ω => CoHomF[Ω] = new CompositeCell {}.ohmToCoHom >>> netL0.ohmToCoHom >>> combinedStateChannel.ohmToCoHom

  val test1: CoHomF[Ω] => CoHomF[Ω] = new CompositeCell {}.mixHomCoHom[Ω] >>> netL0.mixHomCoHom[Ω] >>> combinedStateChannel.mixHomCoHom[Ω]

  val tryChain: CoHomF[Ω] = new CompositeCell {} <~> netL0 <~> combinedStateChannel
  val tryChain2 = new CompositeCell {} <~> netL0 <~> combinedStateChannel

  val composeChain: CoHomF[Ω] => CoHomF[Ω] = tryChain.mixHomCoHom[Ω] >>> tryChain2.mixHomCoHom[Ω]

  def compile(terminal: CoHomF[Ω], chain: CoHomF[Ω] => CoHomF[Ω]) = chain(terminal)

  /** Pass in first class objects that inherit from Ω, so scripts, streams etc.
    */
  val dummyBlock: Ω = InformAboutRoundStartFailure("")

  /** Here's a computation object, like a Future, which returns a result once it's finished, it can be transpiled via the transverse
    * operator in coHomToListCoCell to a CoCell and passed to runtime.
    */
  val actionObject: CoHomF[Ω] = computationObject(dummyBlock)

  val test = fixed2(actionObject)
  val newComposed = netL0 <~> combinedStateChannel

  val cellProgram = (args: List[String]) =>
    for {
      cellInstances: CoCell.Context[CoCell] <- newComposed.coCell.setup(args)
    } yield cellInstances :: Nil
}

// todo val subscribing to L0 endpoint after submission of block to get snapshot with SwapTxs in it, >>> with computeWithStateChannelCell and show results
// todo modify to get snapshot with validated txs out
//  val computeWithStateChannelCell2: IO[Option[Either[CellError, Ω]]] = cellProgram(Nil).flatMap { contexts: Seq[CoCell.Context[CoCell]] =>
//    val thing: Option[Context[CoCell]] = CoCell.getCoCellByName(contexts)(MixedCoCell.getClass.getName)
////    val thing2 = CoCell.getCoCellByName(contexts)(MixedCoCell.getClass.getName)
////    val newProgram: Option[Ω => CoHomF[Ω]] = thing.flatMap(t => thing2.map((tt: Context[CoCell]) => tt.toCoHom >>> t.toCoHom))
//    val coCellContext = CoCell
//      .getCoCellByName(contexts)(MixedCoCell.getClass.getName)
//      .flatMap { c: Context[CoCell] =>
////        val newProgram: Option[Ω => CoHomF[Ω]] = thing.map(_.coCell.toCoHom >>> c.toCoHom)
////        val newProgram: Option[Ω => CoHomF[Ω]] = thing.flatMap(_ >>> c)
//        val cellInstance: CoHomF[Ω => Cell[IO, StackF, Ω, Ω, Either[CellError, Ω]]] = c.coFlatMap(_.mkCell[IO])
////        val t: Option[CoHomF[Ω]] = newProgram.map(_(swapTxs))
//        val cellProgram = thing.map(_.ohmToCoHom >>> cellInstance.ohmToCoHom)
//        cellProgram
//      }
//      .sequence
//    coCellContext
//  } // todo make .toCoHom(
