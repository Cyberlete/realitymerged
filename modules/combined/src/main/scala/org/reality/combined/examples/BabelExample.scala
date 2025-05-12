package org.reality.combined.examples

import cats.implicits.{toComposeOps, toTraverseOps}

import org.reality.combined.{CoCell, CoHomF, Portal}
import org.reality.kernel.Ω

class BabelExample extends Portal {
  import org.reality.combined.CoCell._
  println("correctMain")
  val netL0 = new NetL0 {}
  val combinedStateChannel = new NetStateChannel {}

  val computationObject: Ω => CoHomF[Ω] = new CompositeCell {}.ohmToCoHom >>> netL0.ohmToCoHom >>> combinedStateChannel.ohmToCoHom

  val test1: CoHomF[Ω] => CoHomF[Ω] = netL0.mixHomCoHom[Ω] >>> combinedStateChannel.mixHomCoHom[Ω]

  val tryChain: CoHomF[Ω] = new CompositeCell {} <~> netL0 <~> combinedStateChannel
  val tryChain2 = new CompositeCell {} <~> netL0 <~> combinedStateChannel

  val composeChain: CoHomF[Ω] => CoHomF[Ω] = tryChain.mixHomCoHom[Ω] >>> tryChain2.mixHomCoHom[Ω]

  def compile(terminal: CoHomF[Ω], chain: CoHomF[Ω] => CoHomF[Ω]) = chain(terminal)

  /** todo use CoProduct for Portal.fixed
    */
  val cellProgram = (args: List[String]) =>
    for {
      cellInstances <- CoCell.coHomToListCoCell[Ω](compile(new CompositeCell {}, test1)).map(_.setup(args)).sequence
    } yield cellInstances

}
