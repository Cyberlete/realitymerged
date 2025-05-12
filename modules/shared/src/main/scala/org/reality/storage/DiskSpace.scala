package org.reality.storage

trait DiskSpace[F[_]] {
  def getUsableSpace: F[Long]

  def getOccupiedSpace: F[Long]
}
