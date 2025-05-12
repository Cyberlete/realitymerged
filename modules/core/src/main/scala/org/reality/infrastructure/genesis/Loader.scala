package org.reality.infrastructure.genesis

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.functor._

import org.reality.infrastructure.genesis.types.{GenesisAccount, GenesisCSVAccount}

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text

trait Loader[F[_]] {
  def load(path: Path): F[Set[GenesisAccount]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[GenesisCSVAccount]()
        )
        .map(_.toGenesisAccount)
        .map(_.bimap(e => new RuntimeException(e), identity))
        .rethrow
        .compile
        .toList
        .map(_.toSet)
}
