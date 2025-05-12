package org.reality.combined

class ListOfCoCell(val coCells: List[CoCell]) {
  override def toString: String = s"ListOfCoCell(${coCells.map(_.name).mkString(", ")})"
}
