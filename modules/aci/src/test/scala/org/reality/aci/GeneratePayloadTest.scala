package org.reality.aci

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.file.{Files, Paths}

import org.reality.kernel.Ω
import org.reality.kryo.JsonSerializer

import io.circe.Encoder
import io.circe.generic.semiauto._

case class SomeInput(amount: Int) extends Ω

object GeneratePayloadTest extends App {
  println("Starting serialization and writing to file...")

  val input = SomeInput(10_000_000)

  println("Creating Encoder instance...")
  val someInputEncoder: Encoder[SomeInput] = deriveEncoder[SomeInput]
  println("Serializing data...")
  val bytes = JsonSerializer.serialize(input)(someInputEncoder)

  val directoryPath = "/tmp"
  val filePath = s"$directoryPath/state-channel-input"

  println(s"Checking if directory exists: $directoryPath")
  if (!Files.exists(Paths.get(directoryPath))) {
    try {
      println("Directory doesn't exist. Creating directory...")
      Files.createDirectories(Paths.get(directoryPath))
    } catch {
      case e: Exception =>
        println(s"An error occurred while creating directory: ${e.getMessage}")
        System.exit(1)
    }
  } else {
    println("Directory already exists.")
  }

  println(s"Writing serialized data to file: $filePath")

  val bos = new BufferedOutputStream(new FileOutputStream(filePath))

  try {
    bos.write(bytes)
    println("Data successfully written to file.")
  } catch {
    case e: Exception =>
      println(s"An error occurred while writing to file: ${e.getMessage}")
  } finally {
    bos.close()
    println("File stream closed.")
  }
}
