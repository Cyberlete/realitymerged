package org.reality.combined

// Base trait for all errors
trait BaseError {
  def message: String
}

// Base trait for all WASM-related errors
sealed trait WasmError extends BaseError

case class ExecutionError(msg: String) extends WasmError {
  def message: String = s"Execution error: $msg"
}

case class FunctionNotFound(name: String) extends WasmError {
  def message: String = s"Function $name not found"
}

case class ProofGenerationErrorWasm(msg: String) extends WasmError {
  def message: String = s"Proof generation error: $msg"
}

// ZK-specific errors
sealed trait ZKProofError extends WasmError // Changed to extend WasmError instead of BaseError

case class SetupError(msg: String) extends ZKProofError {
  def message: String = s"Setup error: $msg"
}

case class ProofGenerationError(msg: String) extends ZKProofError {
  def message: String = s"Proof generation error: $msg"
}

case class ProofVerificationError(msg: String) extends ZKProofError {
  def message: String = s"Proof verification error: $msg"
}
