package org.reality.dag.l1.cli

import org.reality.ext.decline.decline._

import com.monovore.decline.Opts
import fs2.io.file.Path

object aciDb {

  val opts: Opts[Path] = Opts
    .option[Path]("aci-db-path", "ACI db home directory")
    .orElse(Opts.env[Path]("CL_ACI_DB_PATH", help = "Aci db home path"))
}
