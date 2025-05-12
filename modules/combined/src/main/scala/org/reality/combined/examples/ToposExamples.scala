package org.reality.combined.examples

import java.util.Base64

import cats.Monoid
import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Concurrent, IO}
import cats.implicits._

import org.reality.combined.{CoCell, CoHomF, CoTopos, EthStateChannel, Portal, Topos}
import org.reality.dag.domain.block.DAGBlock
import org.reality.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext}
import org.reality.domain.cell.AlgebraCommand._
import org.reality.domain.cell.CoalgebraCommand.{Empty, ProcessDAGL1, ProcessStateChannelSnapshot}
import org.reality.domain.cell.L0Cell.Coalgebra
import org.reality.domain.cell.L0CellInput.{HandleDAGL1, HandleStateChannelSnapshot}
import org.reality.domain.cell.{AlgebraCommand, L0Cell}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel._
import org.reality.modules.{AdditionalRoutes, HttpApi, L0Storages, MakeAdditionalRoutes}
import org.reality.schema.address.Address
import org.reality.schema.transaction._
import org.reality.security.signature.Signed
import org.reality.security.{Hashed, SecurityProvider}
import org.reality.statechannel.StateChannelOutput

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.circe.Decoder.Result
import io.circe.DecodingFailure
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.jsonEncoder
import org.http4s.{HttpRoutes, Request}

//import org.reality.kernel.{Cell, CellError, Done, More, StackF, Ω}

object SwapCell {
  def mkCell[F[_]: Async]: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data =>
    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueStateChannelSnapshot(snapshot) =>
                //              Algebra.enqueueStateChannelSnapshot(stateChannelOutputQueue)(snapshot)
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
              case EnqueueDAGL1Data(data) =>
                //              Algebra.enqueueDAGL1Data(Queue[F, Signed[DAGBlock]])(data)
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
              case NoAction =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
              case EnqueueETHL1Data(data) =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
          case ProcessDAGL1(data)                    => Coalgebra.processDAGL1(data)
          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          case _                                     => Coalgebra.empty()
        }
      ),
      {
        case HandleDAGL1(data)                    => ProcessDAGL1(data)
        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
        case _                                    => Empty()
      }
    )
}
import io.circe.Json
import io.circe.generic.codec.DerivedAsObjectCodec.deriveCodec

/* make type in CoData _: HttpApi[F] => Http4sDsl[F], make sure it picks up implicit in HttpApi
 * async$F$0: Description for async$F$0
 * securityProvider$F$1: Description for securityProvider$F$1
 * nodeApi: Description for nodeApi
 * F: Description of the type parameter F
 */

abstract class DataTransactionRoutes[F[_]: Async: SecurityProvider](implicit nodeApi: HttpApi[F]) extends AdditionalRoutes[F] {

  case class SendRecordDataTransactionParams(
    destination: Address,
    fee: TransactionFee,
    data: String
  )

  private def createRecordDataTransactionF(
    nodeApi: HttpApi[F],
    params: SendRecordDataTransactionParams,
    parent: TransactionReference = TransactionReference.empty
  ): F[Json] = {
    val nodePublicAddress = nodeApi.selfId.toAddress
    val dataBytes = Base64.getDecoder.decode(params.data)
    val amount = TransactionAmount(PosLong.MinValue)
    val fee = TransactionFee(NonNegLong.MinValue)
    val salt = Random.scalaUtilRandom.map(_.nextLong.map(TransactionSalt.apply))
    val recordDataTransaction: F[RecordDataTransaction] =
      nodePublicAddress.flatMap(npa => salt.flatMap(s => s.map(RecordDataTransaction(npa, params.destination, "", amount, fee, parent, _))))

    for {
      pk <- nodeApi.selfId.value.toPublicKey
      rdt <- recordDataTransaction
      signedTx: Signed[RecordDataTransaction] <- Signed.forAsyncJson(rdt, nodeApi.key)
      hashedTx: Hashed[RecordDataTransaction] <- signedTx.toHashed
    } yield
      Json.obj(
        "status" -> Json.fromString("Transaction Sent"),
        "transactionHash" -> Json.fromString(hashedTx.hash.toString)
      )

  }

  def handleActionF(
    nodeApi: HttpApi[F],
    action: String,
    params: Json
  ): F[Json] =
    action match {
      case "sendRecordDataTransaction" =>
        params.as[SendRecordDataTransactionParams] match {
          case Right(validParams) => createRecordDataTransactionF(nodeApi, validParams)
          case _                  => Json.obj().pure[F]
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

object DataTransactionRoutes extends MakeAdditionalRoutes {

  def mkRoutes[F[_]: Async: SecurityProvider](implicit nodeApi: HttpApi[F]): AdditionalRoutes[F] = new DataTransactionRoutes {}
}

case class MixedCoCell() extends Dex {
  override val name = this.getClass.getName

  def stopLoss(o: Ω): Boolean = true

  override val coData = CoData( // todo make Parlay, Rule objects with mk methods? then get implicits when called from scope of callsite?
    Parlay :: Nil, // Parlay[EnqueueDAGL1Data]((o: EnqueueDAGL1Data) => o) :: Nil,
    DataTransactionRoutes :: Nil,
    Rule :: Nil // new Rule[Ω] { val fcn = (o: Ω) => if (stopLoss(o)) o else new org.reality.kernel.Empty {} } :: Nil
  ) // AdditionalRoutes

  def mkCell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[DAGBlock]],
    stateChannelOutputQueue: Queue[F, StateChannelOutput]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val left = L0Cell.mkCell(l1OutputQueue, stateChannelOutputQueue)
    val right = SwapCell.mkCell
    Cell.cellMonoid[F, StackF].combine(left(data), right(data))
  }
}

case class MixedChannelCoCell() extends CoCell {
  def mkCell[F[_]: Async: SecurityProvider: cats.effect.std.Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell(ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EthStateChannel.mkCell(ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
  }
}

case class Tokenomics(tokenName: String, fee: Long, maxValue: Long)

case class SwapPair(tokenTo: Tokenomics, tokenFrom: Tokenomics)

trait Rule[O <: Ω] {
  val fcn: O => O
}

abstract class RuleF[F[_]: Async, O <: Ω] {
  val fcn: O => F[Option[O]]
}

trait MkRule {
  def mkRule[F[_]: Async, O <: Ω](default: O)(implicit storages: L0Storages[F]): RuleF[F, O]

  val stopLossPrice = 100L

  // todo get working with F so we can pass in with mkCell during endpoint or statechannel creation
  def stopLoss[F[_]: Concurrent](o: Ω)(implicit storages: L0Storages[F]): F[Boolean] =
    storages.globalSnapshot.getLatestBalances.map(
      _.get.forall(_._2.value > stopLossPrice)
    ) // todo use internal resource to map over mempool
}

/** Pass in pure function which contains extra logic to add between CoCells, essentially we can oracalize data to make decisions within the
  * pure function
  */
case class Parlay[O <: Ω](fcn: O => O)
    extends Rule[O] //todo carry from the left, pass extra parlay logic to filter l1 consensus based on the logic

object Parlay extends MkRule {
  def mkRule[F[_]: Async, O <: Ω](default: O)(implicit storages: L0Storages[F]): RuleF[F, O] = new RuleF[F, O] {
    val fcn: O => F[Option[O]] = { (o: O) =>
      for {
        t: Option[O] <- stopLoss[F](o).ifM(Option(o).pure[F], Option(default).pure[F])
      } yield t
    }
  }
}

object Rule extends MkRule {
  def mkRule[F[_]: Async, O <: Ω](default: O)(implicit storages: L0Storages[F]): RuleF[F, O] = new RuleF[F, O] {
    val fcn: O => F[Option[O]] = { (o: O) =>
      for {
        t: Option[O] <- stopLoss[F](o).ifM(Option(o).pure[F], Option(default).pure[F])
      } yield t
    }
  }
}

/** Pure functions for fees,swap transfer rules, governance vote countrules.
  */
case class Swap[O <: Ω](val fcn: O => O, swapRules: List[SwapPair])
    extends Rule[O] //todo carry from the left, defines swap pairs passes to L1 and validates that it has them, or rather validates that the sum of all right has them
//todo make the dex api a pre-built interface which new tokens add to when they deploy

/** shit each cocell to the right needs to make data structures for shit on the left
  *
  * @param parlays
  * @param endpoints
  * @param rules
  */
case class CoData(parlays: List[MkRule], endpoints: List[MakeAdditionalRoutes], rules: List[MkRule]) extends Ω {}

/** If you want to actually launch a token you'll undoubdetly want some sort of validatore rewards (mining), transaction app fees, endpoints
  * or even custom logic like only making a swap if its raining in Buffalo (Parlay). All of these are "co-things" to CoCells, which means we
  * can make an API for invoking and applying them, namely we can use a Topos which is a special type of monad which allows us to pass in
  * extra data.
  */
object CoData {
  implicit val monoid = new Monoid[CoData] {
    override def empty: CoData = CoData(Nil, Nil, Nil)
    override def combine(x: CoData, y: CoData): CoData = CoData(x.parlays ++ y.parlays, x.endpoints ++ y.endpoints, x.rules ++ y.rules)
  }
}

class ToposExamples extends Portal {
  import CoCell._
  val combinedL0 = MixedCoCell()

  val combinedStateChannel = MixedChannelCoCell()

  val mergedCells = combinedL0 ++ combinedStateChannel
  val testTopos: Seq[CoTopos] = Topos.run(mergedCells) // _.coCell.newCoCellWithCoData(_.coData)
  val cellProgram = (args: List[String]) =>
    for {
      cellInstancesReduced <- testTopos.map(_.coCell).reduce(_ <~> _).setup(args) // .map(_.coCell.setup(args)).sequence

    } yield cellInstancesReduced :: Nil // ++ cellInstances

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
