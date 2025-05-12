package org.reality.dag.l1.app

import java.nio.file.{Files, Paths}

import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.Sync
import cats.effect.std.Queue
import cats.effect.{Async, Fiber}
import cats.implicits.{toFlatMapOps, toFunctorOps}

import org.reality.aci._
import org.reality.ext.collection.MapRefUtils

class AppContext[F[_]: Async](
  val repository: ACIRepository[F],
  val registry: ACIRegistry[F],
  val stateChannelProcessor: StateChannelProcessor[F]
) {
  def startProcessingStateChannelQueue: F[Fiber[F, Throwable, Unit]] =
    stateChannelProcessor.startProcessingQueue.start
}

object AppContext {

  def make[F[_]: Async]: F[AppContext[F]] = {

    val dbDirectoryPath = "/app/tmp"
    val dbPath = s"$dbDirectoryPath/aci.db"

    Files.createDirectories(Paths.get(dbDirectoryPath))

    val runtimeCache = MapRefUtils.ofConcurrentHashMap[F, String, StateChannelRuntime]()

    for {
      repository <- Sync[F].delay(new ACIRepository[F](dbPath))
      runtimeLoader <- Sync[F].delay(new RuntimeLoader[F]())
      inputQueue <- Queue.unbounded[F, (Array[Byte], StateChannelRuntime)]
      registry = new ACIRegistry[F](repository, runtimeLoader, runtimeCache)
      stateChannelProcessor = new StateChannelProcessor[F](inputQueue, registry)
      appContext = new AppContext[F](repository, registry, stateChannelProcessor)
      _ <- appContext.repository.initDb
      _ <- appContext.startProcessingStateChannelQueue
    } yield appContext
  }
}
