package org.reality.combined.examples

import cats.effect.std.{Queue, Random}
import cats.effect.{Async, IO, Resource}
import cats.implicits._

import org.reality.combined.{CoCell, Portal, StartUp}
import org.reality.dag.domain.block.DAGBlock
import org.reality.dag.l1.domain.consensus.block._
import org.reality.dag.l1.modules._
import org.reality.dag.l1.{L1, MkStateChannel}
import org.reality.domain.cell.L0Cell
import org.reality.kernel.{Ω, _}
import org.reality.modules.HttpApi
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

/** CoCells are Cell factories, on input they build up cell monads which are later torn down. This oneCalls a Cell, adds it to another Cell,
  * without type checking. This is good for just adding functionality to already built Cells.
  */
case class CombinedL0() extends CoCell {
  def mkCell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[DAGBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val left: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = L0Cell.mkCell(l1OutputQueue, stateChannelOutputQueue)
    val right: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EmptyCellObj.mkCell
    Cell.cellMonoid[F, StackF].combine(left(data), right(data))
  }
  override def argsToStartUp(args: List[String]): StartUp = StartUp(args)
}

/** Calls a Statechannel, adds it to another Statechannel. Statechannels are an fs2 (stream) api which create and object, within which you
  * can build a pipeline around your cell
  */
case class CombinedStateChannel() extends CoCell {
  val stateChannel: MkStateChannel = L1
  override def mkResources[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] =
    L1HttpApi.mkResources(_: A, _: SDK[IO])
  def mkCell[F[_]: Async: SecurityProvider: Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell[F](ctx)

  override def setup(args: List[String]): IO[CoCell.Context[CoCell]] =
    setupCombined[org.reality.dag.l1.cli.Run](
      stateChannel :: Nil,
      this,
      argsToStartUp(args).l1method,
      org.reality.dag.l1.cli.method.opts,
      L1HttpApi.mkResources(_: org.reality.dag.l1.cli.Run, _: SDK[IO])
    )
}

class CombinedMonoidExample extends Portal {
  import CoCell._
  println("correctMain")
  val combinedL0: CombinedL0 = CombinedL0()
  val combinedStateChannel: CombinedStateChannel = CombinedStateChannel()

  /** Here <~> is a "monoid" which is a fancy + that adds CoCells together, the result is a List of them which is then passed to the
    * runtime, forming the program that becomes the node. This is akin to duck typing. Each node can run many CoCells and thus mine many
    * different tokens
    */
  val mergedCells: Seq[CoCell] = combinedL0 ++ combinedStateChannel

  /** You have to put this here, it is the thing that goes to the runtime
    */
  val cellProgram: List[String] => IO[Seq[Context[CoCell]]] = (args: List[String]) =>
    for {
      cellInstances <- mergedCells.map(_.setup(args)).sequence
    } yield cellInstances

}
