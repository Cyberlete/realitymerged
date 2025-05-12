package org.reality.sdk.domain.snapshot

import cats.syntax.order._

import org.reality.dag.snapshot.GlobalSnapshot
import org.reality.ext.cats.syntax.next.catsSyntaxNext
import org.reality.schema.height.SubHeight
import org.reality.security.Hashed

object Validator {

  def isNextSnapshot(previous: Hashed[GlobalSnapshot], next: GlobalSnapshot): Boolean =
    compare(previous, next).isInstanceOf[Next]

  def compare(previous: Hashed[GlobalSnapshot], next: GlobalSnapshot): ComparisonResult = {
    val isLastSnapshotHashCorrect = previous.hash === next.lastSnapshotHash
    lazy val isNextOrdinal = previous.ordinal.next === next.ordinal
    lazy val isNextHeight = previous.height < next.height && next.subHeight === SubHeight.MinValue
    lazy val isNextSubHeight = previous.height === next.height && previous.subHeight.next === next.subHeight

    if (isLastSnapshotHashCorrect && isNextOrdinal && isNextHeight)
      NextHeight
    else if (isLastSnapshotHashCorrect && isNextOrdinal && isNextSubHeight)
      NextSubHeight
    else
      NotNext
  }

  sealed trait ComparisonResult
  case object NotNext extends ComparisonResult
  sealed trait Next extends ComparisonResult
  case object NextHeight extends Next
  case object NextSubHeight extends Next
}
