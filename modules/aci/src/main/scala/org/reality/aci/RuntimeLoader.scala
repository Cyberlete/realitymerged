package org.reality.aci

import java.net.URL

import cats.effect.{Resource, Sync}
import cats.syntax.all._

import scala.io.Source

import org.reality.aci.config.StateChannelManifest

import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import pureconfig.generic.auto._

class RuntimeLoader[F[_]](implicit F: Sync[F]) {

  private val logger = Slf4jLogger.getLogger[F]

  def loadRuntimeWithAppIdentifier(loader: ClassLoader, manifestUrl: URL, appIdentifier: String): F[StateChannelRuntime] =
    for {
      manifestStr <- readManifest(manifestUrl)
      manifestObj <- loadManifest(manifestStr)
      runtime <- createRuntimeWithAppIdentifier(loader, manifestObj, appIdentifier)
      _ <- logger.info(s"Runtime created for address ${runtime.address}")
    } yield runtime

  def loadRuntime(loader: ClassLoader, manifestUrl: URL): F[StateChannelRuntime] =
    for {
      manifestStr <- readManifest(manifestUrl)
      manifestObj <- loadManifest(manifestStr)
      runtime <- createRuntime(loader, manifestObj)
      _ <- logger.info(s"Runtime created for address ${runtime.address}")
    } yield runtime

  private def readManifest(url: URL): F[String] =
    Resource
      .fromAutoCloseable(F.delay {
        Source.fromURL(url)
      })
      .use { source =>
        F.delay {
          source.mkString
        }
      }

  private def loadManifest(manifestStr: String): F[StateChannelManifest] =
    ConfigSource.string(manifestStr).load[StateChannelManifest] match {
      case Right(scInfo) => F.pure(scInfo)
      case Left(value)   => F.raiseError[StateChannelManifest](new RuntimeException(value.prettyPrint()))
    }

  private def createRuntimeWithAppIdentifier(
    loader: ClassLoader,
    manifest: StateChannelManifest,
    appIdentifier: String
  ): F[StateChannelRuntime] = // TODO; Test it!
    F.delay {
      val cellClass = loader.loadClass(manifest.cellClass)
      val inputClass = loader.loadClass(manifest.inputClass)
      val inputClassDecoder = loader.loadClass(s"${manifest.inputClass}Decoder").asInstanceOf[Decoder[_]]
      val inputClassEncoder = loader.loadClass(s"${manifest.inputClass}Encoder").asInstanceOf[Encoder[Any]]

      new StateChannelRuntime(
        appIdentifier,
        cellClass,
        inputClass,
        inputClassDecoder,
        inputClassEncoder
      )
    }

  private def createRuntime(loader: ClassLoader, manifest: StateChannelManifest): F[StateChannelRuntime] = // TODO: Test it!
    F.delay {
      val cellClass = loader.loadClass(manifest.cellClass)
      val inputClass = loader.loadClass(manifest.inputClass)
      val inputClassDecoder = loader.loadClass(s"${manifest.inputClass}Decoder").asInstanceOf[Decoder[_]]
      val inputClassEncoder = loader.loadClass(s"${manifest.inputClass}Encoder").asInstanceOf[Encoder[Any]]

      new StateChannelRuntime(
        manifest.address,
        cellClass,
        inputClass,
        inputClassDecoder,
        inputClassEncoder
      )
    }

}
