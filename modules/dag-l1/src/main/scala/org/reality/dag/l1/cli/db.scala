package org.reality.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import org.reality.dag.l1.config.types.L1DBConfig
import org.reality.ext.decline.decline._

import ciris.Secret
import com.monovore.decline._
import com.monovore.decline.refined._
import eu.timepit.refined.types.string.NonEmptyString

object db {

  val driverOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_DRIVER", help = "Database driver class")
    .withDefault(NonEmptyString.unsafeFrom("org.sqlite.JDBC"))

  val urlOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_URL", help = "Database URL")
    .withDefault(NonEmptyString.unsafeFrom("jdbc:sqlite:nodedb"))

  val userOpts: Opts[NonEmptyString] = Opts
    .env[NonEmptyString]("CL_DB_USER", help = "Database user")
    .withDefault(NonEmptyString.unsafeFrom("sa"))

  val passwordOpts: Opts[Secret[String]] = Opts
    .env[Secret[String]]("CL_DB_PASSWORD", help = "Database password")
    .withDefault(Secret(""))

  val opts = (driverOpts, urlOpts, userOpts, passwordOpts).mapN(L1DBConfig)
}
