package org.reality.combined.node

// Node type enum
sealed trait NodeType
object NodeType {
  case object L0 extends NodeType
  case object L1 extends NodeType

  def valueOf(str: String): NodeType = str match {
    case "L0" => L0
    case "L1" => L1
    case _    => throw new IllegalArgumentException(s"Unknown node type: $str")
  }
}
