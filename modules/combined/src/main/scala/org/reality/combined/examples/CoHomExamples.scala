package org.reality.combined.examples

import cats.effect.std.Queue
import cats.effect.{Async, IO, Resource}
import cats.implicits.toTraverseOps

import org.reality.combined._
import org.reality.dag.domain.block.DAGBlock
import org.reality.dag.l1.domain.consensus.block.AlgebraCommand.InformAboutRoundStartFailure
import org.reality.dag.l1.modules.L1HttpApi
import org.reality.dag.l1.{L1, MkStateChannel}
import org.reality.domain.cell.L0Cell
import org.reality.kernel._
import org.reality.modules.HttpApi
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

import org.http4s.HttpRoutes

trait NetL0 extends CoCell {

  def mkCell[F[_]: Async](
    implicit l1OutputQueue: Queue[F, Signed[DAGBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] =
    L0Cell.mkCell[F](l1OutputQueue, stateChannelOutputQueue)
  override def argsToStartUp(args: List[String]) = StartUp(args)
}

trait NetStateChannel extends CoCell {
  override val stateChannels: List[MkStateChannel] = L1 :: Nil
  override def mkResources[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] =
    L1HttpApi.mkResources(_: A, _: SDK[IO])
  override def getEndpoints[F[_]: Async]: List[(List[HttpRoutes[F]], List[HttpRoutes[F]])] = List((Nil, Nil))
}

class CellApiExamples extends Portal {
  import CoCell._
  val myL0CoCell = new NetL0 {}
  val myStateChannelCoCell = new NetStateChannel {}

  val cells = myL0CoCell <~> myStateChannelCoCell

  val otherAppPipeline = myL0CoCell :: myStateChannelCoCell

  val cellChain = cells <~> otherAppPipeline.reduce(_ <~> _)

  val cellProgram: List[String] => IO[Seq[CoCell.Context[CoCell]]] = (args: List[String]) =>
    for {
      cellInstances: CoCell.Context[CoCell] <- cellChain.setup(args) // cellChain.map(_.setup(args)).sequence
    } yield cellInstances :: Nil

  /*
Pass in first class objects that inherit from Ω, so scripts, streams etc.
   */
  val dummyBlock: Ω = InformAboutRoundStartFailure("")

  /** You can pass input into a Cell by coFlatmapping over it, this returns the Cell, unexecuted (run() executes)
    */
  val executedResult: Cell[IO, StackF, Ω, Ω, Either[CellError, Ω]] =
    myL0CoCell.coMonad.coflatMap(myL0CoCell.ohmToCoHom(dummyBlock))(_.coCell.mkCell[IO]).start(dummyBlock)

  /** You can turn your runtime into a Future of some computation, basically you can make a meta-program out of all the consensus processes
    * you want.
    */
  val computeWithStateChannelCell: IO[Option[Either[CellError, Ω]]] = cellProgram(Nil).flatMap { contexts: Seq[CoCell.Context[CoCell]] =>
    val coCellContext = CoCell
      .getCoCellByName(contexts)(MixedCoCell.getClass.getName)
      .map { c: Context[CoCell] =>
        val cellInstance: CoHomF[Ω => Cell[IO, StackF, Ω, Ω, Either[CellError, Ω]]] = c.coFlatMap(_.mkCell[IO])
        val cellProgram = cellInstance.start(c).run()
        cellProgram
      }
      .sequence
    coCellContext
  }
}
