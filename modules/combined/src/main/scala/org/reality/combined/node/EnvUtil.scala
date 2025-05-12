package org.reality.combined.node

import cats.effect.IO

object EnvUtil {
  def envVarOrError(key: String): IO[String] =
    Option(System.getenv(key))
      .fold(IO.raiseError[String](new Exception(s"Environment variable $key not set")))(IO.pure)

  def stripAndKeepValues(args: List[String], prefix: String): List[String] =
    args
      .sliding(2, 2)
      .collect {
        case arg1 :: arg2 :: Nil if arg1.startsWith(prefix) => List(arg1.stripPrefix(prefix), arg2)
      }
      .toList
      .flatten

  def extractFlagValueReturnFiltered(args: List[String], commandFlag: String, defaultCommand: String): (String, List[String]) = {
    val (command, remainingArgs) = args
      .sliding(2)
      .collectFirst {
        case List(flag, value) if flag == commandFlag => (Some(value), args.diff(List(flag, value)))
      }
      .getOrElse((None, args))
    (command.getOrElse(defaultCommand), remainingArgs)
  }
}
