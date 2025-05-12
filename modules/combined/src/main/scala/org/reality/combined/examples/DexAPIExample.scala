package org.reality.combined.examples

import java.util
import java.util.Optional

import cats.effect.std.Queue
import cats.effect.{Async, IO, Resource}
import cats.implicits._

import org.reality.combined.{CoCell, EthStateChannel, Portal}
import org.reality.dag.domain.block.DAGBlock
import org.reality.dag.l1.MkStateChannel
import org.reality.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext}
import org.reality.dag.l1.modules.{EmptyCellObj, L1HttpApi}
import org.reality.domain.cell.L0Cell
import org.reality.ext.crypto.RefinedHashable
import org.reality.kernel._
import org.reality.modules.{AdditionalRoutes, HttpApi, MakeAdditionalRoutes}
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.sdk.app.SDK
import org.reality.sdk.cli.CliMethod
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed
import org.reality.statechannel.StateChannelOutput

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import io.circe.Decoder.Result
import io.circe.{DecodingFailure, Json}
import io.github.kawamuray.wasmtime._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoder
import org.http4s.{HttpRoutes, Request, Uri}

abstract class WasmError extends Exception {
  def message: String
}

case class FunctionNotFound(functionName: String) extends WasmError {
  def message: String = s"Function $functionName not found."
}

case class ExecutionError(details: String) extends WasmError {
  def message: String = s"Execution error: $details."
}

case object WasmExecutorUnavailable extends WasmError {
  def message: String = "WasmExecutor instance is not available."
}

class WasmExecutor(input: Ω) extends AutoCloseable with Ω {
  private val store: Store[Void] = Store.withoutData()
  private val engine: Engine = store.engine()
  private var module: Option[Module] = None
  private var instance: Option[Instance] = None

  // Setup the WASM module with the given binary
  def setup(wasmBinary: Array[Byte]): Unit = {
    // Clean up previous instance and module if they exist
    instance.foreach(_.close())
    module.foreach(_.close())

    module = Some(Module.fromBinary(engine, wasmBinary))
    instance = Some(new Instance(store, module.get, util.Collections.emptyList()))
  }

  def executeFunction[T, R](name: String, params: T)(
    implicit paramsConverter: T => Val,
    resultConverter: Array[Val] => R
  ): Either[WasmError, R] =
    instance match {
      case Some(inst) =>
        val funcOpt: Optional[Func] = inst.getFunc(store, name)
        Right(resultConverter(funcOpt.get().call(store)))
      case None =>
        Left(WasmExecutorUnavailable)
    }

  /** todo, return Ω => Ω out of executeFunction()
    * @return
    */
  def exeFcn = input

  // Clean up resources
  def close(): Unit = {
    instance.foreach(_.close())
    module.foreach(_.close())
    store.close()
    engine.close()
  }
}

/* make type in CoData _: HttpApi[F] => Http4sDsl[F], make sure it picks up implicit in HttpApi
 * @param async$F$0
 * @param securityProvider$F$1
 * @param nodeApi
 * @tparam F
 */

abstract class DexRoutes[F[_]: Async: SecurityProvider](implicit nodeApi: HttpApi[F]) extends AdditionalRoutes[F] {

  case class SendRecordDataTransactionParams(
    destination: Address,
    fee: TransactionFee,
    data: String
  )

  private def makeSwapTransaction(swapTx: SwapTx, nodeApi: HttpApi[F]): F[Json] = {
    val obj = Json.obj("status" -> Json.fromString("Transaction Sent"), "transactionHash" -> Json.fromString(swapTx.hash.toString))
    for {
      _ <- nodeApi.httpApi.cliApp.run(
        Request[F](method = POST, uri = Uri.unsafeFromString(s"http://localhost:9000/input/$obj"))
      ) // todo enqueue txs
    } yield obj
  }

  def handleActionF(
    nodeApi: HttpApi[F],
    action: String,
    params: Json
  ): F[Json] =
    action match {
      case "swapTxs" =>
        params.as[SwapTx] match {
          case Right(validParams) => makeSwapTransaction(validParams, nodeApi)
          case _                  => Json.obj().pure[F]
          //          case Left(error)        => new Exception(s"Invalid params: $error").pure[F]
        }
      case _ => Json.obj().pure[F]

    }
  private def handleRequest(nodeApi: HttpApi[F], request: Request[F]): F[Either[DecodingFailure, F[Json]]] =
    for {
      json <- request.as[Json]
      action: Result[String] <- json.hcursor.get[String]("action").pure[F]
      payload: Result[Json] <- json.hcursor.get[Json]("payload").pure[F]
      apiResponse: Either[DecodingFailure, F[Json]] <- action.flatMap(str => payload.map(handleActionF(nodeApi, str, _))).pure[F]
    } yield apiResponse

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "api" =>
      handleRequest(nodeApi, req).flatMap {
        case Right(result)      => Ok(result)
        case Left(errorMessage) => BadRequest(Json.obj("error" -> Json.fromString(errorMessage.toString())))
      }
  }

  val publicRoutes: HttpRoutes[F] = routes
  val p2pRoutes: HttpRoutes[F] = HttpRoutes.empty[F]

}

object DexRoutes extends MakeAdditionalRoutes {

  def mkRoutes[F[_]: Async: SecurityProvider](implicit nodeApi: HttpApi[F]): AdditionalRoutes[F] = new DataTransactionRoutes {}
}

/** todo fee example where tx validated on subledger to reduce fee on net Lomega ledger and total fees overall
  */
trait Dex extends CoCell {
//  val executible: Ω => WasmExecutor = new WasmExecutor(_)
  val netFee = TransactionFee
  val netSwapFee = 1L
  def validateFee(o: Ω): Boolean = true
  def swapFeeTotal(o: Ω) = if (validateFee(o)) o else new Hom[Nothing, Nothing] {}
//  override val coData: CoData = CoData(
//    Parlay(executible(_: Ω).exeFcn) :: Nil, // todo put fee logic in exe?
//    DexRoutes :: Nil,
//    Swap(swapFeeTotal(_), List(SwapPair(Tokenomics("$net", 1L, 1000000000L), Tokenomics("$eth", 1L, 120024000L)))) :: Nil
//  ) // todo put here

}

case class EthSwapDex() extends Dex {

  override val name = this.getClass.getName

//  override val coData = CoData(Nil, DataTransactionRoutes :: Nil, Nil) // AdditionalRoutes
  def mkCell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[DAGBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val left: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = L0Cell.mkCell(l1OutputQueue, stateChannelOutputQueue)
    val right: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EmptyCellObj.mkCell
    Cell.cellMonoid[F, StackF].combine(left(data), right(data))
  }
}

case class EthSwapStatechannelDex() extends CoCell {
  val stateChannel: MkStateChannel = EthStateChannel
  override def mkResources[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] =
    L1HttpApi.mkResources(_: A, _: SDK[IO])
//  def mkCell[F[_]: Async: SecurityProvider: Random](
//                                                     ctx: BlockConsensusContext[F]
//                                                   ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell[F](ctx)

  def mkCell[F[_]: Async: SecurityProvider: cats.effect.std.Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell(ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EthStateChannel.mkCell[F](ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
  }
  override def setup(args: List[String]) =
    setupCombined[org.reality.dag.l1.cli.Run](
      stateChannel :: Nil,
      this,
      argsToStartUp(args).l1method,
      org.reality.dag.l1.cli.method.opts,
      L1HttpApi.mkResources(_: org.reality.dag.l1.cli.Run, _: SDK[IO])
    )

}

class DexAPIExample extends Portal {

  val sendSignedEthToBid =
    SwapTx(
      Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC"),
      Address(
        "DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC"
      ), // todo need wrapper or parent Address trait, want EthAddress here (Address checks validity at instantiation)
      TransactionAmount(PosLong(1L)),
      TransactionAmount(PosLong(1L)),
      TransactionAmount(PosLong(1L)),
      TransactionFee(PosLong(1L)),
      TransactionReference.empty,
      TransactionSalt(0L)
    ) // todo requires lookup on Dex api for open bids

  val matchBid: SwapTx =
    SwapTx(
      Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC"),
      Address(
        "DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC"
      ), // todo need wrapper or parent Address trait, want EthAddress here (Address checks validity at instantiation)
      TransactionAmount(PosLong(1L)),
      TransactionAmount(PosLong(1L)),
      TransactionAmount(PosLong(1L)),
      TransactionFee(PosLong(1L)),
      TransactionReference.empty,
      TransactionSalt(0L)
    ) // todo requires lookup on Dex api for open bids

//  val web3 = Web3j.build(new HttpService());
//  val credentials = WalletUtils.loadCredentials("password", "/path/to/walletfile");
//  val blockStream: Flowable[EthBlock] = web3.blockFlowable(true)
//  val blockStreamIterator = blockStream.blockingMostRecent()
//  val subscription = web3.blockFlowable(true).subscribe(block -> )
  /** //this step happens on ethereum, using web3j, get block with eth-validated result then pass below. Internally, when the block is
    * accepted into a snapshot, the Net funds will be transferred upon snapshot acceptance.
    */
//  val transactionReceipt =
//    Transfer.sendFunds(web3, credentials, "0x<address>|<ensName>", java.math.BigDecimal.valueOf(1.0), Convert.Unit.ETHER).send();
  val ethSwapDemo = CombinedL0() ++ EthSwapStatechannelDex()

  /** todo check fee, execute executible, show results
    */
//  val testTopos = Topos.run(ethSwapDemo)

//    .map { ct: CoTopos =>
//    ct.coCell.newCoCellWithCoData(ct.coData)
//  } // todo put at end of coHomToListCoCell? This should enforce braiding. Do it next time u see this

  val cellProgram = (args: List[String]) =>
    for {
      cellInstancesReduced <- ethSwapDemo.map(_.setup(args)).sequence
      // .reduce(_ <~> _).setup(args) // .map(_.coCell.setup(args)).sequence

    } yield cellInstancesReduced // ++ cellInstances

}
