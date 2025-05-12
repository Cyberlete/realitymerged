package org.reality.aci.endpoint

import cats.data.OptionT
import cats.effect._
import cats.effect.std.{MapRef, Queue}
import cats.implicits._

import org.reality.aci.StateChannelRuntime

import fs2._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, _}
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Not used in ACI
//case class L1Transaction(a: Long, src: String, dst: String, parentHash: String = "", ordinal: Int = 0) extends Ω {
//  val hash = s"$a$src$dst$ordinal$parentHash"
//
//  override def toString: String =
//    s"L1Transaction($hash)"
//}
//
//object L1Transaction {}
//
//case class L1Block(txs: Set[L1Transaction]) extends Ω {}

class StateChannelHandler[F[_]: Async](
  inputQueue: Queue[F, (Array[Byte], StateChannelRuntime)],
  runtimeCache: MapRef[F, String, Option[StateChannelRuntime]]
) extends Http4sDsl[F] {

  private val logger = Slf4jLogger.getLogger[F]

  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]
  implicit val encoder: EntityEncoder[F, Array[Byte]] = EntityEncoder.byteArrayEncoder[F]

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "input" / address =>
        for {
          _ <- logger.info(s"Received input for address $address")
          payload <- req.as[Array[Byte]]
          result <- OptionT(runtimeCache(address).get).semiflatMap { runtime =>
            inputQueue.offer((payload, runtime)) >> Ok(runtime.toString)
          }.getOrElseF(NotFound(s"State Channel not found for address $address"))
        } yield result
    }
}

// Add back to app at later date; not used in ACI
//  val l1Input: Stream[F, (Array[Byte], StateChannelRuntime)] = inputQueue.dequeue
//
//  val l1: Pipe[F, (Array[Byte], StateChannelRuntime), L1Block] =
//    (in: Stream[F, (Array[Byte], StateChannelRuntime)]) =>
//      in.evalTap { case (_, runtime) => logger.info(s"Handling input for address ${runtime.address}") }.evalMap {
//        case (inputBytes, runtime) =>
//          for {
//            input <- runtime.deserializeInput(inputBytes)
//            cell = runtime.createCell[F](input)
//          } yield cell
//      }.evalMap(_.run()).flatMap {
//        case Left(error) => Stream.raiseError(error)
//        case Right(block: L1Block) => Stream.eval(logger.info(s"Output block $block")).as(block)
//        case Right(_: NullTerminal) => Stream.eval(logger.info(s"Terminal object")) >> Stream.empty
//        case e@_ => Stream.raiseError(CellError(s"Invalid cell output type $e"))
//      }
//
//}

object StateChannelHandler {
  def init[F[_]: Async](runtimeCache: Map[String, StateChannelRuntime] = Map()): Stream[F, StateChannelHandler[F]] =
    Stream.eval {
      for {
        inputQueue <- Queue.unbounded[F, (Array[Byte], StateChannelRuntime)]
        runtimeCache <- MapRef.ofSingleImmutableMap(runtimeCache)
      } yield new StateChannelHandler[F](inputQueue, runtimeCache)
    }
}
