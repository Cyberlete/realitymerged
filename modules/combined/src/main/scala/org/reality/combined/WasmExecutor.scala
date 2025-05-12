package org.reality.combined

import java.nio.file.{Files, Path, Paths}
import java.util

import cats.effect.{Async, Resource}
import cats.implicits._

import io.github.kawamuray.wasmtime.{Engine, Instance, Module, Store, Val}
import org.bouncycastle.crypto.digests.SHA256Digest

class WasmExecutor[F[_]: Async] {
  private val defaultParamsPath = "./params"
  private val defaultOutputPath = "./output"
  private val proofDirectory = "./proofs"

  private def initializeDirectories: F[Unit] =
    Async[F].delay {
      List(defaultParamsPath, defaultOutputPath, proofDirectory).foreach { path =>
        Files.createDirectories(Paths.get(path))
      }
    }

  private val engineResource: Resource[F, Engine] =
    Resource.make(Async[F].delay(new Engine()))(engine => Async[F].delay(engine.close()))

  private def storeResource(engine: Engine): Resource[F, Store[Void]] =
    Resource.make(Async[F].delay(new Store[Void](null, engine)))(store => Async[F].delay(store.dispose()))

  private def moduleResource(engine: Engine, wasmBinary: Array[Byte]): Resource[F, Module] =
    Resource.make(Async[F].delay(Module.fromBinary(engine, wasmBinary)))(module => Async[F].delay(module.close()))

  private def instanceResource(store: Store[Void], module: Module): Resource[F, Instance] =
    Resource.make(
      Async[F].delay(new Instance(store, module, util.Collections.emptyList()))
    )(instance => Async[F].delay(instance.dispose()))

  def setup(wasmBinary: Array[Byte]): Resource[F, (Store[Void], Instance)] =
    for {
      engine <- engineResource
      store <- storeResource(engine)
      module <- moduleResource(engine, wasmBinary)
      instance <- instanceResource(store, module)
    } yield (store, instance)

  private def generateAndSaveProof(
    wasmBinary: Array[Byte],
    functionName: String,
    inputs: Array[Val],
    timestamp: Long
  ): F[Either[WasmError, Path]] = {
    val execution = Async[F].delay {
      // Create SHA256 digest
      val digest = new SHA256Digest()

      // Hash WASM binary
      digest.update(wasmBinary, 0, wasmBinary.length)

      // Hash function name
      val nameBytes = functionName.getBytes
      digest.update(nameBytes, 0, nameBytes.length)

      // Hash inputs
      inputs.foreach { input =>
        val bytes = input.i32().toString.getBytes
        digest.update(bytes, 0, bytes.length)
      }

      // Generate commitment hash
      val commitment = new Array[Byte](digest.getDigestSize)
      digest.doFinal(commitment, 0)

      // Create proof path and metadata path
      val proofDest = Paths.get(s"$proofDirectory/${functionName}_${timestamp}.proof")
      val metadataPath = Paths.get(s"$proofDirectory/${functionName}_${timestamp}.meta")

      // Generate proof content
      val proofContent = Seq(
        s"Function: $functionName",
        s"Timestamp: $timestamp",
        s"Commitment: ${commitment.map("%02x".format(_)).mkString}",
        "Inputs:",
        inputs.zipWithIndex.map { case (val_, idx) => s"  input_$idx: ${val_.i32()}" }.mkString("\n"),
        s"Binary Hash: ${commitment.take(8).map("%02x".format(_)).mkString}"
      ).mkString("\n")

      // Generate metadata content
      val metadataContent = s"""
                               |timestamp: $timestamp
                               |function: $functionName
                               |commitment: ${commitment.map("%02x".format(_)).mkString}
                               |input_count: ${inputs.length}
                               |binary_size: ${wasmBinary.length}
      """.stripMargin

      // Save files
      Files.write(proofDest, proofContent.getBytes)
      Files.write(metadataPath, metadataContent.getBytes)

      Right(proofDest): Either[WasmError, Path]
    }

    // Map success case and handle errors
    val withMappedTypes = execution.map {
      case Right(path) => Right(path): Either[WasmError, Path]
      case Left(error) => Left(error): Either[WasmError, Path]
    }

    withMappedTypes.handleErrorWith { e =>
      val error: WasmError = ExecutionError(s"Proof generation error: ${e.getMessage}")
      Async[F].pure(Left(error))
    }
  }

  def executeFunction[T, R](
    store: Store[Void],
    instance: Instance,
    name: String,
    params: T,
    wasmBinary: Array[Byte]
  )(
    paramsConverter: T => Array[Val],
    resultConverter: Array[Val] => R
  ): F[Either[WasmError, (R, Option[Path])]] = {
    val program = for {
      _ <- initializeDirectories
      wasmParams = paramsConverter(params)
      timestamp = System.currentTimeMillis()
      result <- Async[F].delay {
        val funcOpt = Option(instance.getFunc(store, name))
        funcOpt match {
          case Some(func) if func.isPresent =>
            val wasmFunc = func.get()
            val result = wasmFunc.call(store, wasmParams: _*)
            Right(resultConverter(result)): Either[WasmError, R]
          case Some(_) =>
            Left(FunctionNotFound(name)): Either[WasmError, R]
          case None =>
            Left(FunctionNotFound(name)): Either[WasmError, R]
        }
      }
      proofResult <- result match {
        case Right(executionResult) =>
          generateAndSaveProof(wasmBinary, name, wasmParams, timestamp)
            .map(_.map(path => (executionResult, Some(path)))): F[Either[WasmError, (R, Option[Path])]]
        case Left(error) =>
          Async[F].pure(Left(error): Either[WasmError, (R, Option[Path])])
      }
    } yield proofResult: Either[WasmError, (R, Option[Path])]

    program.handleErrorWith { e =>
      val error = ExecutionError(e.getMessage)
      Async[F].pure(Left(error): Either[WasmError, (R, Option[Path])])
    }
  }
}
