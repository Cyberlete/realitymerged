package org.reality.combined

import cats.effect.{Async, IO}

import org.reality.dag.l1.MkStateChannel
import org.reality.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext}
import org.reality.dag.l1.modules.L1HttpApi
import org.reality.kernel.{Cell, CellError, StackF, Ω}
import org.reality.sdk.app.SDK
import org.reality.security.SecurityProvider

case class DataHashCoCell() extends CoCell {
  val stateChannel: MkStateChannel = DataHashStateChannel

  def mkCell[F[_]: Async: SecurityProvider: cats.effect.std.Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell(ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = DataHashStateChannel.mkCell[F](ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
  }

  override def setup(args: List[String]): IO[CoCell.Context[CoCell]] =
    setupCombined[org.reality.dag.l1.cli.Run](
      stateChannel :: Nil,
      this,
      argsToStartUp(args).l1method,
      org.reality.dag.l1.cli.method.opts,
      L1HttpApi.mkResources(_: org.reality.dag.l1.cli.Run, _: SDK[IO])
    )
}
