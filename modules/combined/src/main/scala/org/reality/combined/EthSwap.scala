package org.reality.combined

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.kernel.Sync
import cats.effect.std.{Random, Semaphore}
import cats.effect.{Async, IO}
import cats.implicits._
import cats.{Applicative, Traverse}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.DurationInt

import org.reality.dag.l1.domain.consensus.block.AlgebraCommand._
import org.reality.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.reality.dag.l1.domain.consensus.block.CoalgebraCommand.{EnqueueETHBlock, EnqueueETHSwaps}
import org.reality.dag.l1.domain.consensus.block._
import org.reality.dag.l1.domain.consensus.round.RoundId
import org.reality.dag.l1.http.p2p.L1P2PClient
import org.reality.dag.l1.modules._
import org.reality.dag.l1.{L1, MkStateChannel, StateChannel, WasmExecutionParams}
import org.reality.domain.cell.{ETHL1Block, EthL1Transaction, L1Edge}
import org.reality.kernel.Cell.NullTerminal
import org.reality.kernel.{Cell, CellError, Done, Hom, More, StackF, Ω}
import org.reality.schema.BlockReference
import org.reality.schema.address.Address
import org.reality.schema.height.Height
import org.reality.schema.peer.PeerId
import org.reality.sdk.config.types.AppConfig
import org.reality.security.SecurityProvider
import org.reality.security.hash.ProofsHash

import eu.timepit.refined.types.numeric.PosLong
import fs2.Stream
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.web3j.protocol.core.methods.response.EthGasPrice

case class NativeETHTransaction(
  nonce: String,
  gasPrice: Long,
  startGas: Long,
  to: String,
  value: Long
)

object NativeETHTransaction {

  def getRandom(): NativeETHTransaction = NativeETHTransaction(
    scala.util.Random.nextString(30),
    scala.util.Random.nextLong(),
    scala.util.Random.nextLong(),
    scala.util.Random.nextString(20),
    scala.util.Random.nextLong()
  )
}
case class ETHBlock(transactions: Set[NativeETHTransaction] = Set.empty) extends Ω {}
case class ETHEmission(nativeETHTransaction: NativeETHTransaction, dagAddress: String) extends Ω {}

sealed trait EthCoHom[A] extends Hom[Ω, A] with Ω

case class ReceivedETHEmission[A](emission: ETHEmission) extends EthCoHom[A]

case class ReceivedETHBlock[A](block: ETHBlock) extends EthCoHom[A]

case class WaitForCorrespondingBlockTimeout[A]() extends EthCoHom[A]

case class ETHSwapEnd[A](block: ETHL1Block) extends EthCoHom[A]

import eu.timepit.refined.auto._
import org.reality.schema.transaction._

case class SwapBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Transaction]
) extends Ω

object EthCellObj extends StateChannelCell {
  val parent1 = BlockReference(Height(4L), ProofsHash("parent1"))
  val parent2 = BlockReference(Height(5L), ProofsHash("parent2"))
  val swapTx = SwapTx(
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
  )
//  val block = DAGBlock(NonEmptyList.one(parent2), NonEmptySet.fromSetUnsafe(SortedSet(forAsyncJson(swapTx, ).signed)(HashedOrdering)))

  val ethSideTxHashes = List("")
  val ethSwaps = swapTx :: Nil

  def mkCell[F[_]: Async: SecurityProvider: Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data =>
    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case ProcessSwaps(s, o) =>
                val res = SwapBlock(NonEmptyList.one(parent2), NonEmptySet.fromSetUnsafe(SortedSet.from(s))).pure[F]
                res.map(_.asRight[CellError].widen[Ω])

              case InformAboutRoundStartFailure(message) =>
                val res: F[Either[CellError, Ω]] = CellError(message).asLeft[Ω].pure[F]
                res
              case _ => NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, Ω] {
//          case ProcessDAGL1(data)                    => Coalgebra.processDAGL1(data)
          case EnqueueETHBlock(data: OmegaEthBlockWrapper) => // todo lookups in if (ctx.transactionStorage.waitingTransactions)
            if (ethSideTxHashes.exists(data.ethBlock.getBlock.getTransactions.contains(_)))
              Applicative[F].pure(Done(InformAboutRoundStartFailure("").asRight[CellError]))
            else Applicative[F].pure(Done(ProcessSwaps(ethSwaps, data :: Nil).asRight[CellError]))
          case EnqueueETHSwaps(swaps: SwapWrapper) => // todo lookups in if (ctx.transactionStorage.waitingTransactions)
            if (ethSideTxHashes.exists(txHash => swaps.swaps.exists(_.parent.hash.value == txHash)))
              Applicative[F].pure(Done(InformAboutRoundStartFailure("").asRight[CellError]))
            else Applicative[F].pure(Done(ProcessSwaps(swaps.swaps, Nil).asRight[CellError]))

          //          case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          case _ => Applicative[F].pure(Done(org.reality.dag.l1.domain.consensus.block.AlgebraCommand.NoAction.asRight[CellError]))
        }
      ),
      {
        case d: OmegaEthBlockWrapper =>
          println(s"EthCellObj OmegaEthBlockWrapper $d")
          EnqueueETHBlock(d)
        case swaps: SwapWrapper =>
          println(s"EthCellObj SwapWrapper $swaps")

          EnqueueETHSwaps(swaps)
        case a @ _ =>
          println(s"EthCellObj a ${a}")
          org.reality.dag.l1.domain.consensus.block.CoalgebraCommand.Empty()
        //        case HandleDAGL1(data)                    => ProcessDAGL1(data)
//        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
//        case _                                    => Empty() // todo delete this
      }
    )
}
object EthStateChannel extends MkStateChannel {
  println("creating EthStateChannel")
  val cellObj: StateChannelCell = EthCellObj
  def make[F[_]: Async: SecurityProvider: Random](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2pClient: L1P2PClient[F],
    programs: L1Programs[F],
    queues: L1Queues[F],
    selfId: PeerId,
    services: L1Services[F],
    storages: L1Storages[F],
    validators: Validators[F],
    mkCell: StateChannelCell,
    wasmPrograms: List[WasmExecutionParams[_, _]] = List.empty
  ): F[StateChannel[F, _, _]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
    } yield
      new EthStateChannel[F](
        appConfig,
        blockAcceptanceS,
        blockCreationS,
        blockStoringS,
        keyPair,
        p2pClient,
        programs,
        queues,
        selfId,
        services,
        storages,
        validators,
        mkCell
      )

  implicit val traverse: Traverse[EthCoHom] = new DefaultTraverse[EthCoHom] {
    override def traverse[G[_]: Applicative, A, B](fa: EthCoHom[A])(f: A => G[B]): G[EthCoHom[B]] =
      fa.asInstanceOf[EthCoHom[B]].pure[G]
  }
}

case class BroadcastProposalResponse(
  roundId: RoundId,
  senderProposals: Set[EthL1Transaction],
  receiverProposals: Set[EthL1Transaction]
)

sealed trait L1ConsensusF[A] extends Hom[Ω, A]

/** Input as owner
  */
case class StartOwnRound[A](edge: L1Edge) extends L1ConsensusF[A]

/** Input as facilitator
  */
case class ReceiveProposal[A](
  roundId: RoundId,
  senderId: String,
  proposal: L1Edge,
  ownEdge: L1Edge
) extends L1ConsensusF[A]

case class BroadcastProposal[A]() extends L1ConsensusF[A]

case class BroadcastReceivedProposal[A]() extends L1ConsensusF[A]

/** Output - error
  */
case class L1Error[A](reason: Throwable) extends L1ConsensusF[A]

/** Output from coalgebra to algebra to create a block
  */
case class ConsensusEnd[A](responses: List[BroadcastProposalResponse]) extends L1ConsensusF[A]

/** Output as facilitator
  */
case class ProposalResponse[A](txs: Set[EthL1Transaction]) extends L1ConsensusF[A]

object L1ConsensusF {
  implicit val traverse: Traverse[L1ConsensusF] = new DefaultTraverse[L1ConsensusF] {
    override def traverse[G[_]: Applicative, A, B](fa: L1ConsensusF[A])(f: A => G[B]): G[L1ConsensusF[B]] =
      fa.asInstanceOf[L1ConsensusF[B]].pure[G]
  }
}

object ETHCell {
  private val logger = Slf4jLogger.getLogger[IO]

  // TODO: Unmock
  def sendETHTransactionToETHChain[F[_]: Async](ethTransaction: NativeETHTransaction): F[Unit] = Sync[F].unit

  // TODO: Unmock
  def waitForCorrespondingETHBlock[F[_]: Async](): F[ReceivedETHBlock[Ω]] =
    Sync[F].sleep(10.seconds).flatMap(_ => ReceivedETHBlock[Ω](ETHBlock()).pure[F])

  // TODO: Unmock
  def updateLiquidityPoolLedger[F[_]: Async](transactions: Set[NativeETHTransaction]): F[Unit] = Sync[F].unit

  // TODO: Unmock
  def createDAGTransactionsForL0[F[_]: Async](block: ETHBlock): F[ETHL1Block] = {
    val ethTransactions = block.transactions.toList
    val dagTransactions = ethTransactions.map(t => EthL1Transaction(t.value.toInt, "A", "B"))
    ETHL1Block(dagTransactions.toSet).pure[F]
  }

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[Ω]]
  def empty[F[_]: Async](): CoalgebraR[F] = {
    def res: StackF[Ω] = Done(NoAction.asRight[CellError])

    res.pure[F]
  }

//  def processEthBlock[F[_]: Async](block: ETHL1Block): CoalgebraR[F] = {
//    val t: StackF[Ω] = Done(AlgebraCommand.EnqueueETHL1Data(block).asRight[CellError])
//    t.pure[F]
//  }

  def processEthError[F[_]: Async](block: CellError): CoalgebraR[F] = {
    val t: StackF[Ω] = Done(block.asLeft[CellError])
    t.pure[F]
  }

  def processReceivedETHEmission[F[_]: Async](emission: ETHEmission): CoalgebraR[F] = for {
    _ <- sendETHTransactionToETHChain(emission.nativeETHTransaction)
    blockOrTimeout: Ω <- waitForCorrespondingETHBlock().handleErrorWith(_ => ReceivedETHBlock[Ω](ETHBlock()).pure[F])
  } yield More(blockOrTimeout)

  //  def processETHSwapEnd[F[_]: Async](result: ETHSwapEnd[ETHL1Block]): F[Either[CellError, Ω]] = {
  //    val t = result.block.asRight[CellError]
  //    t.pure[F]
  //  }

  def processReceivedETHBlock[F[_]: Async](block: ETHBlock): CoalgebraR[F] =
    for {
      _ <- updateLiquidityPoolLedger(block.transactions)
      l1Block: Ω <- createDAGTransactionsForL0(block)
    } yield More(l1Block)

//  def mkCell[F[_]: Async](): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data =>
//    new Cell[F, StackF, Ω, Ω, Either[CellError, Ω]](
//      data,
//      scheme.hyloM(
//        AlgebraM[F, StackF, Either[CellError, Ω]] {
//          case More(a)     => a.pure[F]
//          case Done(other) => other.pure[F]
//        },
//        CoalgebraM[F, StackF, Ω] {
//          case block @ ETHL1Block(txs) =>
//            //            Done(block.asRight[CellError]).pure[F]
//            processEthBlock(block)
//          case error @ CellError(txs) =>
//            processEthError(error)
//          //            Done(error.asLeft[Ω]).pure[F]
//          //          empty()
//
//          case ReceivedETHEmission(emission: ETHEmission) => processReceivedETHEmission(emission)
//
//          case ReceivedETHBlock(block) => processReceivedETHBlock(block)
//          case _                       => empty()
//        }
//      ),
//      {
//        case HandleDAGL1(data)                    => ProcessDAGL1(data)
//        case HandleStateChannelSnapshot(snapshot) => ProcessStateChannelSnapshot(snapshot)
//        case _                                    => Empty()
//      }
//    )

}

//import cats.effect.{ContextShift, IO, Timer}
import org.web3j.protocol.core.methods.response.EthBlock

import org.web3j.protocol.http.HttpService

//
//object Convert {
//
//  def asciiToHex(ascii: String): String = {
//    val chars = ascii.toCharArray
//    val hex = new StringBuffer()
//    for (ch <- chars) {
//      hex.append(Integer.toHexString(ch.toInt))
//    }
//    hex.toString
//  }
//
//  def hexToAscii(hex: String): String = {
//    val bytes = hexStringToByteArray(hex)
//    new String(bytes, StandardCharsets.US_ASCII)
//  }
//}
//
///**
// * Just for testing purposes!
// */
//class ETHTransactionGenerator(
//                               blockchainUrl: String,
//                               liquidityPoolAddress: String,
//                               privateKey: String
//                             ) {
//  private val credentials = Credentials.create(privateKey)
//  private val client: Web3j = Web3j.build(new HttpService(blockchainUrl))
//  private val rawTransactionManager: RawTransactionManager = new RawTransactionManager(client, credentials)
//
//  def createSampleRawTransaction(destinationDAGAddress: String): IO[String] =
//    for {
//      nonce <- getNonce
//      value = org.web3j.utils.Convert.toWei("0.0001", org.web3j.utils.Convert.Unit.ETHER).toBigInteger
//      to = liquidityPoolAddress
//      gasPrice = DefaultGasProvider.GAS_PRICE
//      gasLimit = DefaultGasProvider.GAS_LIMIT
//      data = asciiToHex(destinationDAGAddress)
//      tx = RawTransaction
//        .createTransaction(nonce, gasPrice, gasLimit, to, value, data)
//      signedTx = rawTransactionManager.sign(tx)
//    } yield signedTx
//
//  private def getNonce: IO[BigInteger] =
//    FutureLift.from {
//      IO(client.ethGetTransactionCount(credentials.getAddress, DefaultBlockParameterName.LATEST).sendAsync())
//    }.map(_.getTransactionCount)
//}
//
//object ETHTransactionGenerator {
//
//  def apply(blockchainUrl: String, liquidityPoolAddress: String, privateKey: String): ETHTransactionGenerator =
//    new ETHTransactionGenerator(blockchainUrl, liquidityPoolAddress, privateKey)
//}
///**
// * For testing purposes use here infura.io/alchemy.com testnet gateways
// */
//class ETHBlockchainClient(blockchainUrl: String) {
//  private val client: Web3j = Web3j.build(new HttpService(blockchainUrl))
//  def blocks: Stream[IO, EthBlock] =
//    client
//      .blockFlowable(true)
//      .toStream[IO]
//}

//object ETHBlockchainClient {
//  def apply(blockchainUrl: String): ETHBlockchainClient = new ETHBlockchainClient(blockchainUrl)
//}

class EthStateChannel[F[_]: Async: SecurityProvider: cats.effect.std.Random](
  override val appConfig: AppConfig,
  override val blockAcceptanceS: Semaphore[F],
  override val blockCreationS: Semaphore[F],
  override val blockStoringS: Semaphore[F],
  override val keyPair: KeyPair,
  p2pClient: L1P2PClient[F],
  override val programs: L1Programs[F],
  override val queues: L1Queues[F],
  override val selfId: PeerId,
  override val services: L1Services[F],
  override val storages: L1Storages[F],
  override val validators: Validators[F],
  override val cellObj: StateChannelCell
) extends L1[F](
      appConfig: AppConfig,
      blockAcceptanceS: Semaphore[F],
      blockCreationS: Semaphore[F],
      blockStoringS: Semaphore[F],
      keyPair: KeyPair,
      p2pClient: L1P2PClient[F],
      programs: L1Programs[F],
      queues: L1Queues[F],
      selfId: PeerId,
      services: L1Services[F],
      storages: L1Storages[F],
      validators: Validators[F],
      cellObj: StateChannelCell
    ) {

//  import com.micronautics.web3j._
//  import com.micronautics.web3j.Web3JScala._
//  import org.web3j.protocol.core.DefaultBlockParameterName._
//  import org.web3j.protocol.core.methods.{request, response}
  import org.web3j.protocol.Web3j
//  import org.web3j.protocol.infura.InfuraHttpService
//  def observe[T](observable: Observable[T])
//                (fn: T => Unit): Unit =
//    observable.subscribe(fn(_))

//  def suppressValueDiscard(): Unit = "": Unit

  /** Only runs fn on the first n elements observed from the given Observable[T] */
//  def observe[T](n: Int)(observable: Flowable[T])(fn: T => Unit): Unit =
//    observable.limit(n.toLong).subscribe(fn(_))
//
//  observe(2)(web3.blockFlowable(false)) { ethBlock =>
//    println("observe ethBlock - " + ethBlock)
//  }

//  val filter = new EthFilter(DefaultBlockParameter.valueOf(new BigInteger("3306085")), DefaultBlockParameterName.LATEST, List())
//  web3.ethLogFlowable(filter).subscribe((log) => onNewEvent(log))
//  val credentials = WalletUtils.loadCredentials("password", "/path/to/walletfile");
//def log(blockConsensusInput: EthBlock) =
//  logger.info(s"Pulled BlockConsensusInput from Ethereum: ${blockConsensusInput}")
//  val blockStream: Flowable[EthBlock] = web3.replayPastAndFutureBlocksFlowable(EARLIEST, false).subscribe { ethBlock =>
//    val block = ethBlock.getBlock
//    if (block.getTimestamp.longValue < now) {
//      count = count + 1
//      print(s"\rSkipped $count blocks. ")
//      //      println(s"Skipping ${ block.getNumber }: ${ block.javaTime }")
//    } else println(format(ethBlock))
//  }

//  val tryGet = blockStream.blockingFirst()
//  println("tryGet - " + tryGet)

//  def init(): Stream[IO,EthBlock] =
//    Stream.eval {
//      for {
//        blockchainClient = web3.blockFlowable(true)
//      } yield blockchainClient
//    }

  import fs2.interop.reactivestreams._
  val web3: Web3j = Web3j.build(
    new HttpService("https://sepolia.infura.io/v3/4dfa6f9f4ad94a1aac09596e3fad4e08")
  ) // https://sepolia.infura.io/v3/4dfa6f9f4ad94a1aac09596e3fad4e08
  val testSend: EthGasPrice = web3.ethGasPrice().send()
  println("testSend - " + testSend)
  val ethBlockStream: Stream[F, EthBlock] = web3.blockFlowable(true).toStreamBuffered(1)

  //   It emits ETH blocks periodically pulled from ETH chain
//  def log[A](prefix: String): Pipe[Task, A,A] = _.evalMap{ a => Task.delay{ println(s"$prefix> $a"); a}}
  val logEthBlocks = ethBlockStream.evalTap { b: EthBlock =>
    def log(blockConsensusInput: EthBlock) =
      logger.info(s"Pulled BlockConsensusInput from Ethereum: ${blockConsensusInput}")
    println(s"Pulled BlockConsensusInput from Ethereum: ${b}")
    log(b)
  }
  val periodicalyFetchBlocks: Stream[F, BlockConsensusInput] = logEthBlocks.map((block: EthBlock) => OmegaEthBlockWrapper(block)).repeat

//  val url = "wss://mainnet.infura.io/ws"
//
//  // Connection to the node
//  val web3jService = new WebSocketService(url, true)
//  web3jService.connect()
//  val web3j = Web3j.build(web3jService)
//
//  val clientVersion: String = web3j.web3ClientVersion.send.getWeb3ClientVersion
//  System.out.println(String.format("Connected to Ethereum node %s : %s", url, clientVersion))
//
//  // Subsribe to blocks
//  web3j
//    .blockFlowable(true)
//    .toStreamBuffered(1)
//    .evalTap { b =>
//      def log(blockConsensusInput: EthBlock) =
//        logger.info(s"Pulled BlockConsensusInput from Ethereum: ${blockConsensusInput}")
//      System.out.println("NEW BLOCK -> " + b.getBlock.getNumber.intValue)
//      log(b)
//    }
//    .compile
//    .drain

  // Stream(ETHBlock(Set(NativeETHTransaction.getRandom())))
  //      .evalMap(block => Temporal[F].sleep(5.seconds).as(block))//todo use Temporal[F] in cats 3.x

  // It emits emission requests sent to HTTP API of Node
//  val ethEmissionFromHTTPAPI: Stream[F, ReceivedETHEmission[Ω]] =
//    Stream(ETHEmission(NativeETHTransaction.getRandom(), "DAGSampleAddress"))
//      //      .evalMap(emission => F.sleep(4.seconds).as(emission))//todo use Temporal[F] in cats 3.x
//      .map(emission => ReceivedETHEmission[Ω](emission))
//      .repeat

//  val l1Input: Stream[F, Ω] = periodicalyFetchBlocks.merge(ethEmissionFromHTTPAPI)

  // Whole pipeline is merged and emits all the possible input datatypes for ETHCell
//  val l1: Pipe[F, Ω, ETHBlock] = (in: Stream[F, Ω]) =>
//    // .merge(periodicalyFetchBlocks).merge(ethEmissionFromHTTPAPI)
//    in.map(cellObj.mkCell(blockConsensusContext))
//      .evalMap {
//        case x: Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] => x.run() //// evalMap(c => c.hylo(_))
//      }
//      .map {
//        case Left(error)            => Left(error)
//        case Right(block: ETHBlock) => Right(block)
//        case _                      => Left(CellError("Invalid Ω type"))
//      }
//      .map(_.toOption.get) // TODO: error here

//  override implicit val blockConsensusContext: BlockConsensusContext[F] =
//    BlockConsensusContext[F](
//      p2PClient.blockConsensus,
//      storages.block,
//      validators.block,
//      storages.cluster,
//      org.reality.dag.l1.domain.consensus.block.config.ConsensusConfig(), // todo set config here,
//      storages.consensus,
//      keyPair,
//      selfId,
//      storages.transaction,
//      validators.transaction
//    )
//  val oldInputPipeline: Stream[F, BlockConsensusInput] = super.blockConsensusInputs
  val newBlockConsensus: Stream[F, Unit] = blockConsensusInputs
    .merge(periodicalyFetchBlocks)
    .through(runConsensus)
    .through(gossipBlock)
    .through(sendBlockToL0)
//      .merge(periodicalyFetchBlocks)
    .merge(peerBlocks)
    .through(storeBlock)

  val testEthStream = periodicalyFetchBlocks.map(b => println("periodicalyFetchBlocks" + b)).repeat.void
  println(s"EthStateChannel ${testEthStream}")
//  override val cell: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = cellObj.mkCell(blockConsensusContext)
//  override val runConsensus: Pipe[F, Ω, FinalBlock] = l1.compose(super.runConsensus)
  override val runtime: fs2.Stream[F, Unit] =
    newBlockConsensus.merge(blockAcceptance).merge(globalSnapshotProcessing).merge(l0PeerDiscovery)

}

case class EthStateChannelCoCell() extends CoCell {
  def mkCell[F[_]: Async: SecurityProvider: cats.effect.std.Random](
    ctx: BlockConsensusContext[F]
  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
    val test: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = BlockConsensusCell.mkCell(ctx)
    val other: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EthStateChannel.mkCell(ctx)
    Cell.cellMonoid[F, StackF].combine(test(data), other(data))
    // test >>> other
  }
}

//case class EthL0() extends CoCell {
//  def mkCell[F[_]: Async](
//    l1OutputQueue: Queue[F, Signed[DAGBlock]],
//    stateChannelOutputQueue: Queue[F, StateChannelOutput]
//  ): Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = data => {
//    val left: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = L0Cell.mkCell(l1OutputQueue, stateChannelOutputQueue)
//    val right: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = SwapCell.mkCell
//    Cell.cellMonoid[F, StackF].combine(left(data), right(data))
//    left
//  }
//}
