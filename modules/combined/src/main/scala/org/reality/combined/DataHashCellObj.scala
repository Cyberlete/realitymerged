package org.reality.combined

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.implicits._

import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput.DataHashWrapper
import org.reality.dag.l1.domain.consensus.block.{AlgebraCommand, BlockConsensusContext, CoalgebraCommand, StateChannelCell}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel.{Cell, CellError, Done, More, StackF, Ω}
import org.reality.security.SecurityProvider

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

object DataHashCellObj extends StateChannelCell {
  def mkCell[F[_]: Async: SecurityProvider: Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = { data =>
    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case AlgebraCommand.ProcessDataHash(wrapper) =>
                for {
                  hashedUserTx <- wrapper.value.transaction.toHashed[F]
                  _ <- ctx.transactionStorage.put(hashedUserTx)
                  result <- NullTerminal.asRight[CellError].widen[Ω].pure[F]
                } yield result
              case _ =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) =>
            other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
          case CoalgebraCommand.EnqueueDataHash(wrapper) =>
            Applicative[F].pure(Done(AlgebraCommand.ProcessDataHash(wrapper).asRight[CellError]))
          case _ =>
            Applicative[F].pure(Done(AlgebraCommand.NoAction.asRight[CellError]))
        }
      ),
      {
        case d: DataHashWrapper =>
          println(s"DataHashWrapper $d")
          CoalgebraCommand.EnqueueDataHash(d)
        case _ =>
          println("Empty Command in DataHashCellObj")
          CoalgebraCommand.Empty()
      }
    )
  }

}
