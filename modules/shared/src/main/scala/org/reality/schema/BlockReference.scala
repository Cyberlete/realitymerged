package org.reality.schema

import cats.effect.Async
import cats.syntax.functor._

import org.reality.ext.derevo.ordering
import org.reality.schema.height.Height
import org.reality.security.hash.ProofsHash
import org.reality.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import io.circe.Encoder

@derive(arbitrary, encoder, decoder, order, ordering, show)
case class BlockReference(height: Height, hash: ProofsHash)

object BlockReference {
  def of[F[_]: Async, B <: Block[_]: Encoder](block: Signed[B]): F[BlockReference] =
    block.proofsHash.map(BlockReference(block.height, _))
}
