import cats.effect.IO
import org.reality.combined.CoCell
import org.reality.dag.l1.WasmExecutionParams
import org.reality.dag.l1.modules.L1HttpApi
import org.reality.sdk.app.SDK

case class WasmExecutorCoCell[T, R](wasmProgram: WasmExecutionParams[T, R]) extends CoCell {
  val stateChannel =  CyberleteWasmExecutorStateChannel :: Nil




  override def setup(args: List[String]): IO[CoCell.Context[CoCell]] =
    setupCombined[org.reality.dag.l1.cli.Run](
      stateChannel,
      this,
      argsToStartUp(args).l1method,
      org.reality.dag.l1.cli.method.opts,
      L1HttpApi.mkResources(_: org.reality.dag.l1.cli.Run, _: SDK[IO])
      //      ,
      //      List(wasmProgram)
    )
}
