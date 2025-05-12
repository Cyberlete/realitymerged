package org.reality.combined

import java.nio.file.{Files, Path}

import cats.effect.Sync
import cats.implicits._

import scala.jdk.CollectionConverters._

import org.reality.aci.{ACIRegistry, StateChannelJar}

object DirectoryJarProcessor {

  def processJarsInDirectory[F[_]: Sync](dirPath: Path, aciRegistry: ACIRegistry[F]): F[List[StateChannelJar]] = {
    val jarFiles = Files.list(dirPath).iterator().asScala.filter(_.toString.endsWith(".jar")).toList

    jarFiles.traverse { jarFile =>
      for {
        content <- Sync[F].delay(Files.readAllBytes(jarFile))
        jar <- aciRegistry.createStateChannelJar(content)
      } yield jar
    }
  }
}
