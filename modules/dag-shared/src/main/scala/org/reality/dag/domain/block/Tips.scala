package org.reality.dag.domain.block

import cats.data.NonEmptyList

import org.reality.schema.BlockReference

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class Tips(value: NonEmptyList[BlockReference])
