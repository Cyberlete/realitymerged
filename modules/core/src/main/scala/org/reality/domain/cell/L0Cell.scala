package org.reality.domain.cell

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.domain.block.NETBlock
import org.reality.domain.cell.AlgebraCommand._
import org.reality.domain.cell.CoalgebraCommand._
import org.reality.domain.cell.L0Cell.{Algebra, Coalgebra}
import org.reality.domain.cell.L0CellInput.{HandleNETL1, HandleStateChannelSnapshot}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel._
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

sealed trait L0CellInput extends Ω

object L0CellInput extends Ω {
  case class HandleNETL1(data: Signed[NETBlock]) extends L0CellInput
  case class HandleStateChannelSnapshot(snapshot: StateChannelOutput) extends L0CellInput
  case class Empty() extends L0CellInput
}

class L0Cell[F[_]: Async](
  data: L0CellInput,
  l1OutputQueue: Queue[F, Signed[NETBlock]],
  stateChannelOutputQueue: Queue[F, StateChannelOutput]
) extends Cell[F, StackF, CoalgebraCommand, L0CellInput, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueStateChannelSnapshot(snapshot) =>
                Algebra.enqueueStateChannelSnapshot(stateChannelOutputQueue)(snapshot)
              case EnqueueNETL1Data(data) =>
                Algebra.enqueueNETL1Data(l1OutputQueue)(data)
              case _ =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
          case ProcessNETL1(data)                    => Coalgebra.processNETL1(data)
          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          case _                                     => Coalgebra.empty()
        }
      ),
      {
        case HandleNETL1(data)                    => ProcessNETL1(data)
        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
        case _                                    => Empty()
      }
    )

object L0Cell {
  type Mk[F[_]] = L0CellInput => L0Cell[F]

  def mkCell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[NETBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data =>
    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueStateChannelSnapshot(snapshot) =>
                Algebra.enqueueStateChannelSnapshot(stateChannelOutputQueue)(snapshot)
              case EnqueueNETL1Data(data) =>
                Algebra.enqueueNETL1Data(l1OutputQueue)(data)
              case _ =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
          case ProcessNETL1(data)                    => Coalgebra.processNETL1(data)
          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          case _                                     => Coalgebra.empty()
        }
      ),
      {
        case HandleNETL1(data)                    => ProcessNETL1(data)
        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
        case _                                    => Empty()
      }
    )

  def mkL0Cell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[NETBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Mk[F] =
    data => new L0Cell(data, l1OutputQueue, stateChannelOutputQueue)

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[Ω]] // F[StackF[CoalgebraCommand]]

  object Algebra {

    def enqueueStateChannelSnapshot[F[_]: Async](
      queue: Queue[F, StateChannelOutput]
    )(snapshot: StateChannelOutput): AlgebraR[F] =
      queue.offer(snapshot) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]

    def enqueueNETL1Data[F[_]: Async](queue: Queue[F, Signed[NETBlock]])(data: Signed[NETBlock]): AlgebraR[F] =
      queue.offer(data) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]
  }

  object Coalgebra {

    def processNETL1[F[_]: Async](data: Signed[NETBlock]): CoalgebraR[F] = {
      def res: StackF[Ω] = Done(AlgebraCommand.EnqueueNETL1Data(data).asRight[CellError])

      res.pure[F]
    }

    def processStateChannelSnapshot[F[_]: Async](snapshot: StateChannelOutput): CoalgebraR[F] = {
      def res: StackF[Ω] = Done(AlgebraCommand.EnqueueStateChannelSnapshot(snapshot).asRight[CellError])

      res.pure[F]
    }

    def empty[F[_]: Async](): CoalgebraR[F] = {
      def res: StackF[Ω] = Done(NoAction.asRight[CellError])

      res.pure[F]
    }
  }
}
