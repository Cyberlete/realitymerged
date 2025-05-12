package org.reality.combined

import cats.effect.IO

case class MongoConfig(
  username: String,
  password: String,
  cluster: String,
  database: String = "gametelemetry",
  collection: String = "movements"
) {
  def uri: String =
    s"mongodb+srv://$username:$password@$cluster/?retryWrites=true&w=majority&appName=CyberleteWASM"
}

object MongoConfig {
  def fromEnv: IO[MongoConfig] = IO {
    MongoConfig(
      username = sys.env("MONGO_USERNAME"),
      password = sys.env("MONGO_PASSWORD"),
      cluster = "cyberletewasm.vqx1l8y.mongodb.net",
      database = sys.env.getOrElse("MONGO_DATABASE", "gametelemetry"),
      collection = sys.env.getOrElse("MONGO_COLLECTION", "movements")
    )
  }
}
