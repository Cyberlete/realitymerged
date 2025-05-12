package org.reality.ext.cats.syntax

import org.reality.schema.KeyIndex

trait KeyIndexSyntax {
  implicit def syntaxKeyIndex[A: KeyIndex](a: A): KeyIndexOps[A] =
    new KeyIndexOps[A](a)
}

final class KeyIndexOps[A: KeyIndex](a: A) {
  def index: Long = KeyIndex[A].index(a)
}
