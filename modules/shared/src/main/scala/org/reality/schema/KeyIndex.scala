package org.reality.schema

import scala.{specialized => sp}

trait KeyIndex[@sp A] {
  def index(key: A): Long
}

object KeyIndex {
  def apply[A](implicit N: KeyIndex[A]) = N
}
