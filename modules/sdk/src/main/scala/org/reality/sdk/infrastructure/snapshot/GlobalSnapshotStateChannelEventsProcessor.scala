package org.reality.sdk.infrastructure.snapshot

import cats.Eval
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.reality.dag.snapshot.GlobalSnapshotInfo
import org.reality.ext.cats.syntax.validated._
import org.reality.ext.crypto._
import org.reality.schema.address.Address
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.security.hash.Hash
import org.reality.security.signature.Signed
import org.reality.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.reality.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  def process(
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelOutput]
  ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async](stateChannelValidator: StateChannelValidator[F]) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)
      def process(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])] =
        events
          .traverse(event => stateChannelValidator.validate(event).map(_.errorMap(error => (event.address, error))))
          .map(_.partitionMap(_.toEither))
          .flatTap {
            case (invalid, _) => logger.warn(s"Invalid state channels events: ${invalid}").whenA(invalid.nonEmpty)
          }
          .map { case (_, validatedEvents) => processStateChannelEvents(lastGlobalSnapshotInfo, validatedEvents) }

      private def processStateChannelEvents(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      ): (SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput]) = {

        val lshToSnapshot: Map[(Address, Hash), StateChannelOutput] = events.map { e =>
          (e.address, e.snapshot.value.lastSnapshotHash) -> e
        }.foldLeft(Map.empty[(Address, Hash), StateChannelOutput]) { (acc, entry) =>
          entry match {
            case (k, newEvent) =>
              acc.updatedWith(k) { maybeEvent =>
                maybeEvent
                  .filter(event => Hash.fromBytes(event.snapshot.content) < Hash.fromBytes(newEvent.snapshot.content))
                  .orElse(newEvent.some)
              }
          }
        }

        val result = events
          .map(_.address)
          .distinct
          .map { address =>
            lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes
              .get(address)
              .map(hash => address -> hash)
              .getOrElse(address -> Hash.empty)
          }
          .mapFilter {
            case (address, initLsh) =>
              def unfold(lsh: Hash): Eval[List[StateChannelOutput]] =
                lshToSnapshot
                  .get((address, lsh))
                  .map { go =>
                    for {
                      head <- Eval.now(go)
                      tail <- go.snapshot.hash.fold(_ => Eval.now(List.empty), unfold)
                    } yield head :: tail
                  }
                  .getOrElse(Eval.now(List.empty))

              unfold(initLsh).value.toNel.map(address -> _.map(_.snapshot).reverse)
          }
          .toSortedMap

        (result, Set.empty[StateChannelOutput])
      }
    }

}
