package org.reality.aci

import java.sql.SQLException

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import doobie._
import doobie.implicits._

class ACIRepository[F[_]: Async](private val dbPath: String) {

  private val xa = Transactor.fromDriverManager[F](
    "org.apache.derby.iapi.jdbc.AutoloadedDriver",
    s"jdbc:derby:$dbPath;create=true"
  )

  private val createStateChannelJar: ConnectionIO[Int] =
    sql"""create table state_channel_jar (
         |    id VARCHAR(255) not null,
         |    content blob not null,
         |    primary key(id)
         |)""".stripMargin.update.run

  def initDb: F[Unit] =
    createStateChannelJar
      .transact(xa)
      .void
      .recover { case e: SQLException if e.getSQLState == "X0Y32" => () }

  def saveStateChannelJar(jar: StateChannelJar): F[Int] =
    sql"""insert into state_channel_jar (id, content)
          values (${jar.id}, ${jar.content})
          """.update.run.transact(xa)

  def findStateChannelJar(id: String): OptionT[F, StateChannelJar] =
    OptionT(
      sql"""select s.id, s.content
          from state_channel_jar s
          where s.id = $id"""
        .query[StateChannelJar]
        .option
        .transact(xa)
    )
}
