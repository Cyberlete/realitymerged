package org.reality

import org.reality.kernel._

package object aci {
  type StdCell[F[_]] = Cell[F, StackF, Ω, Either[CellError, Ω], Ω]
}
