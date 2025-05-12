package org.reality.aci

import cats.effect._
import cats.effect.std.Queue
import cats.implicits._

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannelProcessor[F[_]: Async](
  inputQueue: Queue[F, (Array[Byte], StateChannelRuntime)],
  registry: ACIRegistry[F]
) {
  private val logger = Slf4jLogger.getLogger[F]

  def enqueueInput(address: String, payload: Array[Byte]): F[Unit] =
    registry
      .getStateChannelRuntime(address)
      .semiflatMap { runtime =>
        inputQueue.offer((payload, runtime)) >> logger.info(s"Enqueued input for address $address")
      }
      .getOrElseF(logger.warn(s"State Channel not found for address $address"))

  def startProcessingQueue: F[Unit] =
    Stream
      .eval(logger.info("Starting processing queue..."))
      .flatMap { _ =>
        Stream
          .eval(inputQueue.take)
          .repeat
          .evalMap {
            case (inputBytes, runtime) =>
              logger.info(s"Dequeued input for address ${runtime.address}") *>
                runtime.deserializeInput(inputBytes).flatMap { deserializedInput =>
                  val cell = runtime.createCell[F](deserializedInput)
                  cell.run().attempt.flatMap {
                    case Left(error) =>
                      logger.error(error)(s"Failed to process cell for address ${runtime.address}")
                    case Right(output) =>
                      logger.info(s"Successfully processed cell for address ${runtime.address} with output: $output")
                  }
                }
          }
          .handleErrorWith { ex =>
            Stream.eval(logger.error(ex)("An unexpected error occurred while processing the queue"))
          }
      }
      .drain
      .compile
      .drain

}
