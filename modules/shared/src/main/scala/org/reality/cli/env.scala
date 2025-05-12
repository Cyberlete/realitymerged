package org.reality.cli

import cats.syntax.contravariantSemigroupal._

import org.reality.ext.decline.decline._
import org.reality.security.hex.Hex

import ciris.Secret
import com.monovore.decline._
import fs2.io.file.Path
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object env {
  sealed trait EnvOpt

  @newtype
  case class Password(value: Secret[String])

  object Password {
    val opts: Opts[Password] = Opts
      .option[String]("password", "Password for something")
      .map(s => Password(Secret(s)))
      .orElse(Opts.env[Password]("CL_PASSWORD", help = "Password"))
  }

  @newtype
  case class Passphrase(value: Secret[String])

  object Passphrase {
    val opts: Opts[Passphrase] = Opts.option[String]("passphrase", "A passphrase").map(s => Passphrase(Secret(s)))
  }

  @newtype
  case class StorePass(value: Secret[String])

  object StorePass {

    val opts: Opts[StorePass] = Opts
      .env[StorePass]("CL_STOREPASS", help = "Keystore password")
  }

  @newtype
  case class KeyPass(value: Secret[String])

  object KeyPass {

    val opts: Opts[KeyPass] = Opts
      .env[KeyPass]("CL_KEYPASS", help = "Key password")
  }

  @newtype
  case class KeyAlias(value: Secret[String])

  object KeyAlias {
    val opts: Opts[KeyAlias] = Opts
      .option[String]("keyalias", "Alias of key in keystore")
      .map(s => KeyAlias(Secret(s)))
      .orElse(Opts.env[KeyAlias]("CL_KEYALIAS", help = "Alias of key in keystore"))
  }

  @newtype
  case class StorePath(value: Path)

  object StorePath {
    val opts: Opts[StorePath] = Opts
      .option[Path]("keystore", "Keystore path")
      .map(p => StorePath(p))
      .orElse(Opts.env[Path]("CL_KEYSTORE", help = "Keystore path").map(_.coerce))
  }

  case class KeyHex(value: Secret[Hex])

  object KeyHex {
    val opts: Opts[KeyHex] = Opts
      .option[Hex]("keyhex", "Hex of a private key")
      .map(h => KeyHex(Secret(h)))
  }

  type KeyConfig = Either[(StorePath, KeyAlias, Password), KeyHex]

  val keyConfigOpts: Opts[KeyConfig] =
    (Password.opts, KeyAlias.opts, StorePath.opts).mapN { (password, keyAlias, storePath) =>
      Left((storePath, keyAlias, password))
    }.orElse(KeyHex.opts.map(Right(_)))
}
