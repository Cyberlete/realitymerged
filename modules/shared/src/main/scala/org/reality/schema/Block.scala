package org.reality.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.reality.ext.cats.syntax.next._
import org.reality.schema.height.Height
import org.reality.schema.transaction.Transaction
import org.reality.security.signature.Signed

trait Block[A <: Transaction] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[A]]

  val height: Height = parent.maximum.height.next
}
