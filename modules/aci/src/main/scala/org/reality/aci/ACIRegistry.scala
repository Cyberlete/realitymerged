package org.reality.aci

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.Files

import cats.data.OptionT
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits._

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ACIRegistry[F[_]: Async](
  repository: ACIRepository[F],
  runtimeLoader: RuntimeLoader[F],
  runtimeCache: MapRef[F, String, Option[StateChannelRuntime]]
) {
  private val logger = Slf4jLogger.getLogger[F]

  def createStateChannelJarWithAppIdentifier(content: Array[Byte], appIdentifier: String): F[StateChannelJar] =
    for {
      runtime <- loadStateChannelJarWithAppIdentifier(content, appIdentifier)
      jar = StateChannelJar(runtime.address, content)
      _ <- repository.saveStateChannelJar(jar)
      _ <- logger.info(s"Saved ${content.length} under ${jar.id}")
    } yield jar

  def createStateChannelJar(content: Array[Byte]): F[StateChannelJar] =
    for {
      runtime <- loadStateChannelJar(content)
      jar = StateChannelJar(runtime.address, content)
      _ <- repository.saveStateChannelJar(jar)
      _ <- logger.info(s"Saved ${content.length} under ${jar.id}")
    } yield jar

  def getStateChannelRuntime(address: String)(implicit F: Sync[F], C: Concurrent[F]): OptionT[F, StateChannelRuntime] =
    OptionT(runtimeCache(address).get).orElse {
      for {
        jar <- repository.findStateChannelJar(address)
        runtime: StateChannelRuntime <- OptionT.liftF(loadStateChannelJar(jar.content))
        // TODO assert address == runtime.address
        _ <- OptionT.liftF(runtimeCache(runtime.address).set(runtime.some))
      } yield runtime
    }

  private def loadStateChannelJarWithAppIdentifier(content: Array[Byte], appIdentifier: String): F[StateChannelRuntime] =
    Async[F].defer {
      for {
        jarFile <- saveJarToFile(content)
        loader = URLClassLoader.newInstance(Array(jarFile.toURI.toURL), this.getClass.getClassLoader)
        manifestUrl <- findManifestUrl(loader)
        runtime <- runtimeLoader.loadRuntimeWithAppIdentifier(loader, manifestUrl, appIdentifier)
      } yield runtime
    }

  private def loadStateChannelJar(content: Array[Byte]): F[StateChannelRuntime] =
    Async[F].defer {
      for {
        jarFile <- saveJarToFile(content)
        loader = URLClassLoader.newInstance(Array(jarFile.toURI.toURL), this.getClass.getClassLoader)
        manifestUrl <- findManifestUrl(loader)
        runtime <- runtimeLoader.loadRuntime(loader, manifestUrl)
      } yield runtime
    }

  def saveJarToFile(content: Array[Byte]): F[File] = Async[F].delay {
    val tempFile = File.createTempFile("state-channel", "jar")
    Files.write(tempFile.toPath, content)
    tempFile.deleteOnExit()
    tempFile
  }

  def findManifestUrl(loader: URLClassLoader): F[URL] =
    OptionT(Async[F].delay(Option(loader.getResource("state-channel.info")))).getOrElseF(
      Async[F].raiseError(new RuntimeException("state-channel.info not found"))
    )
}
