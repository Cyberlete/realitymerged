package org.reality.combined

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util

import cats.effect.{Async, Resource}
import cats.implicits._

import scala.jdk.OptionConverters._
import scala.util.Try

import org.reality.combined.models.{StarkConfig, StarkProof}

import io.github.kawamuray.wasmtime.{Engine, Instance, Module, Store, Val}
import org.bouncycastle.crypto.digests.SHA256Digest

class ZKWasmExecutor[F[_]: Async] extends WasmExecutor[F] {
  // Configuration and Storage Paths
  private val proofStoragePath = "./proofs"
  private val defaultParamsPath = "./params"
  private val defaultCircuitPath = "./circuits"

  // STARK Configuration
  private val config = StarkConfig(
    fieldPrime = BigInt("21888242871839275222246405745257275088548364400416034343698204186575808495617"),
    traceLength = 1024,
    ldeExpansionFactor = 4,
    friFoldingFactor = 2,
    friLayers = 4,
    friQueries = 8
  )

  // Resource Management
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

  // setup method
  override def setup(wasmBinary: Array[Byte]): Resource[F, (Store[Void], Instance)] =
    for {
      engine <- engineResource
      store <- storeResource(engine)
      module <- moduleResource(engine, wasmBinary)
      instance <- instanceResource(store, module)
    } yield (store, instance)

  // Main Public Methods
  def generateProof(
    name: String,
    publicInputs: List[(String, Any)],
    privateInputs: List[(String, Any)]
  ): F[Either[ZKProofError, Path]] = {
    val timestamp = System.currentTimeMillis()
    val proofPath = Paths.get(s"$proofStoragePath/${name}_$timestamp.proof")

    (for {
      _ <- Async[F].delay(ensureDirectoriesExist())

      // Convert inputs to BigInt mod field
      allInputs = (publicInputs ++ privateInputs).map {
        case (k, v) => (k, toBigInt(v).mod(config.fieldPrime))
      }

      // Generate the STARK proof
      starkProof <- Async[F].delay {
        generateStarkProof(allInputs)
      }

      // Serialize and save proof data
      proofData = serializeStarkProof(starkProof)
      metadataPath = Paths.get(s"${proofPath.toString}.meta")
      metadata = createMetadata(name, starkProof, allInputs)

      _ <- Async[F].delay {
        Files.write(proofPath, proofData)
        Files.write(metadataPath, metadata.getBytes)
      }

    } yield Right(proofPath): Either[ZKProofError, Path]).handleErrorWith { e =>
      Async[F].pure(Left(ProofGenerationError(e.getMessage)))
    }
  }

  override def executeFunction[T, R](
    store: Store[Void],
    instance: Instance,
    name: String,
    params: T,
    wasmBinary: Array[Byte]
  )(
    paramsConverter: T => Array[Val],
    resultConverter: Array[Val] => R
  ): F[Either[WasmError, (R, Option[Path])]] = {
    val wasmParams = paramsConverter(params)

    def execWasm: F[Either[WasmError, R]] = Async[F].delay {
      Option(instance.getFunc(store, name))
        .flatMap(_.toScala)
        .map { func =>
          resultConverter(func.call(store, wasmParams: _*))
        }
        .toRight(FunctionNotFound(name))
    }

    def genProof: F[Either[WasmError, Path]] = {
      val timestamp = System.currentTimeMillis()
      val publicInputs = wasmParams.zipWithIndex.map {
        case (v, i) =>
          val bigV = Try(v.i32()).map(BigInt(_)).getOrElse(BigInt(0))
          (s"input_$i", bigV.mod(config.fieldPrime))
      }.toList

      (for {
        starkProof <- Async[F].delay {
          generateStarkProof(publicInputs)
        }
        proofPath = Paths.get(s"$proofStoragePath/${name}_$timestamp.proof")
        proofData = serializeStarkProof(starkProof)
        metadataPath = Paths.get(s"${proofPath.toString}.meta")
        metadata = createMetadata(name, starkProof, publicInputs)
        _ <- Async[F].delay {
          Files.write(proofPath, proofData)
          Files.write(metadataPath, metadata.getBytes)
        }
      } yield Right(proofPath): Either[WasmError, Path]).handleErrorWith { e =>
        Async[F].pure(Left(ProofGenerationErrorWasm(e.getMessage)): Either[WasmError, Path])
      }
    }

    def processResult[A](result: Either[WasmError, A]): F[Either[WasmError, (A, Option[Path])]] =
      result match {
        case Right(r) =>
          genProof.map {
            case Right(path) => Right((r, Some(path)))
            case Left(error) => Left(error)
          }
        case Left(error) =>
          Async[F].pure(Left(error))
      }

    (for {
      _ <- Async[F].delay(ensureDirectoriesExist())
      result <- execWasm
      finalResult <- processResult(result)
    } yield finalResult).handleErrorWith { e =>
      Async[F].pure(Left(ExecutionError(e.getMessage)))
    }
  }

  // Core STARK Methods
  private def generateExecutionTrace(inputs: List[(String, BigInt)]): Array[BigInt] = {
    val trace = new Array[BigInt](config.traceLength)
    val inputSum = inputs.map(_._2).sum.mod(config.fieldPrime)

    var state = inputSum
    for (i <- 0 until config.traceLength) {
      trace(i) = state
      // Simple state transition: x_{i+1} = x_i^2 + sum(inputs) mod p
      state = (state * state + inputSum).mod(config.fieldPrime)
    }
    trace
  }

  private def lowDegreeExtend(trace: Array[BigInt]): Array[BigInt] = {
    val extendedSize = trace.length * config.ldeExpansionFactor
    val extended = new Array[BigInt](extendedSize)

    // 1. Convert to polynomial coefficients via naive DFT
    val coeffs = naiveDFT(trace, inverse = false)

    // 2. Evaluate at extended set of points
    for (i <- 0 until extendedSize) {
      val x = BigInt(i).mod(config.fieldPrime)
      extended(i) = evaluatePolynomial(coeffs, x)
    }

    extended
  }

  private def zeroKnowledgeMask(lde: Array[BigInt]): (Array[BigInt], Array[BigInt]) = {
    val randomMask = Array.fill(lde.length) {
      BigInt(config.fieldPrime.bitLength, scala.util.Random).mod(config.fieldPrime)
    }

    val masked = lde.zip(randomMask).map {
      case (v, r) => (v + r).mod(config.fieldPrime)
    }

    (masked, randomMask)
  }

  private def merkleRoot(leaves: Array[Array[Byte]]): Array[Byte] = {
    var layer = leaves
    val digest = new SHA256Digest()

    while (layer.length > 1) {
      val newLayer = new Array[Array[Byte]]((layer.length + 1) / 2)
      for (i <- layer.indices by 2) {
        val left = layer(i)
        val right = if (i + 1 < layer.length) layer(i + 1) else left

        digest.reset()
        digest.update(left, 0, left.length)
        digest.update(right, 0, right.length)

        val hash = new Array[Byte](digest.getDigestSize)
        digest.doFinal(hash, 0)
        newLayer(i / 2) = hash
      }
      layer = newLayer
    }
    layer.head
  }

  private def generateFiatShamirChallenge(data: Array[BigInt]): BigInt = {
    val digest = new SHA256Digest()
    data.foreach { value =>
      val bytes = value.toByteArray
      digest.update(bytes, 0, bytes.length)
    }
    val output = new Array[Byte](digest.getDigestSize)
    digest.doFinal(output, 0)
    BigInt(1, output).mod(config.fieldPrime)
  }

  private def runFRI(
    data: Array[BigInt],
    challenge: BigInt
  ): (Seq[Array[BigInt]], Seq[Array[Byte]], Array[BigInt]) = {
    var currentLayer = data
    val layers = scala.collection.mutable.ListBuffer[Array[BigInt]]()
    val merkleRoots = scala.collection.mutable.ListBuffer[Array[Byte]]()

    layers += currentLayer
    merkleRoots += merkleRoot(currentLayer.map(_.toByteArray))

    // Repeatedly fold the polynomial
    for (_ <- 0 until config.friLayers) {
      val newSize = currentLayer.length / config.friFoldingFactor
      val nextLayer = new Array[BigInt](newSize)

      // Fold pairs of elements with the challenge
      for (i <- 0 until newSize) {
        val left = currentLayer(2 * i)
        val right = if (2 * i + 1 < currentLayer.length) currentLayer(2 * i + 1) else left
        // Combine using: left + challenge * right (mod fieldPrime)
        nextLayer(i) = (left + challenge * right).mod(config.fieldPrime)
      }

      currentLayer = nextLayer
      layers += currentLayer
      merkleRoots += merkleRoot(currentLayer.map(_.toByteArray))
    }

    (layers.toSeq, merkleRoots.toSeq, currentLayer)
  }

  // Helper Methods
  private def naiveDFT(values: Array[BigInt], inverse: Boolean): Array[BigInt] = {
    val n = values.length
    val result = new Array[BigInt](n)

    for (k <- 0 until n) {
      var sum = BigInt(0)
      for (j <- 0 until n) {
        // Use simplified trig calculations for demonstration
        val angle = if (inverse) -2 * math.Pi * k * j / n else 2 * math.Pi * k * j / n
        val coefficient = BigInt((math.cos(angle) * 1000).toLong)
        sum = (sum + values(j) * coefficient).mod(config.fieldPrime)
      }
      result(k) = sum
    }
    result
  }

  private def evaluatePolynomial(coeffs: Array[BigInt], x: BigInt): BigInt = {
    var result = BigInt(0)
    var power = BigInt(1)

    for (coeff <- coeffs) {
      result = (result + (coeff * power).mod(config.fieldPrime)).mod(config.fieldPrime)
      power = (power * x).mod(config.fieldPrime)
    }
    result
  }

  private def generateStarkProof(inputs: List[(String, BigInt)]): StarkProof = {
    // Generate execution trace
    val trace = generateExecutionTrace(inputs)

    // Low-degree extension
    val ldeTrace = lowDegreeExtend(trace)

    // Apply zero-knowledge mask
    val (maskedLDE, zkMask) = zeroKnowledgeMask(ldeTrace)

    // Calculate Merkle roots
    val traceMerkleRoot = merkleRoot(trace.map(_.toByteArray))
    val ldeMerkleRoot = merkleRoot(maskedLDE.map(_.toByteArray))

    // Generate FRI proof
    val challenge = generateFiatShamirChallenge(maskedLDE)
    val (friLayers, friRoots, finalPoly) = runFRI(maskedLDE, challenge)

    // Generate randomness for the proof
    val proofRandomness = BigInt(config.fieldPrime.bitLength, scala.util.Random)

    StarkProof(
      trace = trace,
      traceMerkleRoot = traceMerkleRoot,
      ldeTrace = maskedLDE,
      ldeMerkleRoot = ldeMerkleRoot,
      friLayers = friLayers,
      friMerkleRoots = friRoots,
      finalPolynomial = finalPoly,
      zeroKnowledgeMask = zkMask,
      publicInputs = inputs,
      proofRandomness = proofRandomness
    )
  }

  private def serializeStarkProof(proof: StarkProof): Array[Byte] = {
    val sb = new StringBuilder()
    sb.append("==== STARK PROOF ====\n")

    // Trace data
    sb.append(s"Trace (size=${proof.trace.length}):\n")
    sb.append(proof.trace.map(_.toString(16)).mkString(",") + "\n")
    sb.append("Trace Merkle Root:\n")
    sb.append(proof.traceMerkleRoot.map("%02x".format(_)).mkString + "\n")

    // LDE data
    sb.append(s"LDE Trace (size=${proof.ldeTrace.length}):\n")
    sb.append(proof.ldeTrace.map(_.toString(16)).mkString(",") + "\n")
    sb.append("LDE Merkle Root:\n")
    sb.append(proof.ldeMerkleRoot.map("%02x".format(_)).mkString + "\n")

    // FRI layers
    sb.append("FRI Layers:\n")
    proof.friLayers.zipWithIndex.foreach {
      case (layer, idx) =>
        sb.append(s"  Layer $idx (size=${layer.length}): ${layer.map(_.toString(16)).mkString(",")}\n")
    }

    // FRI Merkle roots
    sb.append("FRI Merkle Roots:\n")
    proof.friMerkleRoots.foreach { root =>
      sb.append(s"  ${root.map("%02x".format(_)).mkString}\n")
    }

    // Final polynomial and ZK mask
    sb.append("Final Polynomial:\n")
    sb.append(proof.finalPolynomial.map(_.toString(16)).mkString(",") + "\n")
    sb.append("Zero-Knowledge Mask:\n")
    sb.append(proof.zeroKnowledgeMask.map(_.toString(16)).mkString(",") + "\n")

    // Public inputs and randomness
    sb.append("Public Inputs:\n")
    proof.publicInputs.foreach {
      case (k, v) =>
        sb.append(s"  $k = ${v.toString(16)}\n")
    }
    sb.append(s"Proof Randomness: ${proof.proofRandomness.toString(16)}\n")

    sb.toString().getBytes
  }

  private def createMetadata(
    name: String,
    proof: StarkProof,
    allInputs: List[(String, BigInt)]
  ): String =
    s"""
       |name: $name
       |timestamp: ${System.currentTimeMillis()}
       |trace_root: ${proof.traceMerkleRoot.map("%02x".format(_)).mkString}
       |lde_root: ${proof.ldeMerkleRoot.map("%02x".format(_)).mkString}
       |public_inputs: ${allInputs.map { case (n, v) => s"$n=$v" }.mkString(",")}
       |fri_roots: ${proof.friMerkleRoots.map(r => r.map("%02x".format(_)).mkString).mkString(",")}
       |randomness: ${proof.proofRandomness.toString(16)}
       |""".stripMargin

  private def ensureDirectoriesExist(): Unit =
    List(proofStoragePath, defaultParamsPath, defaultCircuitPath).foreach { path =>
      val dir = new File(path)
      if (!dir.exists()) {
        dir.mkdirs()
      }
    }

  private def toBigInt(value: Any): BigInt = value match {
    case n: Number  => BigInt(n.toString)
    case s: String  => BigInt(s.getBytes.map(_.toInt).sum)
    case b: Boolean => if (b) BigInt(1) else BigInt(0)
    case _          => BigInt(value.toString.getBytes.map(_.toInt).sum)
  }
}
