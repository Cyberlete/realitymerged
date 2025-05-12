package org.reality.cli

import cats.syntax.contravariantSemigroupal._
import cats.syntax.validated._

import org.reality.cli.db
import org.reality.cli.env.{KeyAlias, Password, StorePath}
import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.ext.decline.WithOpts
import org.reality.ext.decline.decline._
import org.reality.schema.balance.Amount
import org.reality.schema.node.NodeState
import org.reality.schema.peer
import org.reality.schema.peer.{L0Peer, PeerId}
import org.reality.sdk.cli.{CliMethod, CollateralAmountOpts}
import org.reality.sdk.config.AppEnvironment
import org.reality.sdk.config.types._
import org.reality.security.hash.Hash

import com.comcast.ip4s.{Host, IpLiteralSyntax, Port}
import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

    // todo check gossip configs?

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: L0DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    startingEpochProgress: EpochProgress,
    l0Peer: peer.L0Peer
  ) extends Run

  object RunGenesis extends WithOpts[RunGenesis] {

    val genesisPathOpts: Opts[Option[Path]] = Opts.argument[Path]("genesis").orNone
    val newGenesisPathOpts: Opts[Option[Path]] = Opts.option[Path]("new-genesis-path", "Description here").orNone

    val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val startingEpochProgressOpts: Opts[EpochProgress] = Opts
      .option[NonNegLong]("startingEpochProgress", "Set starting progress for rewarding at the specific epoch")
      .map(EpochProgress(_))
      .withDefault(EpochProgress.MinValue)
    val l0PeerIdOpts: Opts[PeerId] = Opts // todo just getting any string
      .option[PeerId]("peer-id", help = "L0 peer Id")
      .orElse(Opts.env[PeerId]("CL_L0_PEER_ID", help = "L0 peer Id"))

    val l0PeerHostOpts: Opts[Host] = Opts
      .option[Host]("l0-ip", help = "L0 peer HTTP host")
      .orElse(Opts.env[Host]("CL_L0_PEER_HTTP_HOST", help = "L0 peer HTTP host"))

    val l0PeerPortOpts: Opts[Port] = Opts
      .option[Port]("startup-port", help = "L0 peer HTTP port")
      .orElse(Opts.env[Port]("CL_L0_PEER_HTTP_PORT", help = "L0 peer HTTP port"))
      .withDefault(port"9000")

    val l0PeerOpts: Opts[L0Peer] = (l0PeerIdOpts, l0PeerHostOpts, l0PeerPortOpts)
      .mapN(L0Peer(_, _, _))

    // todo parse Opts for ip info

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        newGenesisPathOpts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        startingEpochProgressOpts,
        l0PeerOpts
      ).mapN {
        (
          storePath,
          keyAlias,
          password,
          dbConfig,
          httpConfig,
          environment,
          snapshotConfig: SnapshotConfig,
          genesisPath,
          newGenesisPath,
          seedlistPath,
          collateralAmount,
          startingEpochProgress,
          l0PeerOpts
        ) =>
          if (genesisPath.isDefined && newGenesisPath.isDefined) {
            throw new IllegalArgumentException("Both genesis path options are provided. Only one is allowed.")
          }

          val finalGenesisPath = genesisPath.orElse(newGenesisPath).getOrElse {
            throw new IllegalArgumentException("No genesis path is provided.")
          }

          RunGenesis(
            storePath,
            keyAlias,
            password,
            dbConfig,
            httpConfig,
            environment,
            snapshotConfig,
            finalGenesisPath,
            seedlistPath,
            collateralAmount,
            startingEpochProgress,
            l0PeerOpts
          )
      }
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: L0DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    rollbackHash: Hash,
    genesisPath: Path,
    l0Peer: peer.L0Peer
  ) extends Run

  object RunRollback extends WithOpts[RunRollback] {

    val genesisPathOpts =
      (Opts.argument[Path]("genesis").orNone, Opts.option[Path]("new-genesis-path", "Description here").orNone).tupled.mapValidated {
        case (Some(_), Some(_)) => "Both genesis path options are provided. Only one is allowed.".invalidNel[Path]
        case (None, None)       => "No genesis path is provided.".invalidNel[Path]
        case (Some(path), None) => path.validNel[String]
        case (None, Some(path)) => path.validNel[String]
      }

    val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val rollbackHashOpts: Opts[Hash] = Opts.argument[Hash]("rollbackHash")
    val l0PeerIdOpts: Opts[PeerId] = Opts
      .option[PeerId]("peer-id", help = "L0 peer Id")
      .orElse(Opts.env[PeerId]("CL_L0_PEER_ID", help = "L0 peer Id"))

    val l0PeerHostOpts: Opts[Host] = Opts
      .option[Host]("l0-ip", help = "L0 peer HTTP host")
      .orElse(Opts.env[Host]("CL_L0_PEER_HTTP_HOST", help = "L0 peer HTTP host"))

    val l0PeerPortOpts: Opts[Port] = Opts
      .option[Port]("startup-port", help = "L0 peer HTTP port")
      .orElse(Opts.env[Port]("CL_L0_PEER_HTTP_PORT", help = "L0 peer HTTP port"))
      .withDefault(port"9000")

    val l0PeerOpts: Opts[L0Peer] = (l0PeerIdOpts, l0PeerHostOpts, l0PeerPortOpts)
      .mapN(L0Peer(_, _, _))

    val opts = Opts.subcommand("run-rollback", "Run rollback mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        rollbackHashOpts,
        genesisPathOpts,
        l0PeerOpts
      ).mapN(RunRollback.apply(_, _, _, _, _, _, _, _, _, _, _, _))
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: L0DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[Path],
    collateralAmount: Option[Amount],
    l0Peer: peer.L0Peer
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val seedlistPathOpts: Opts[Option[Path]] = Opts.option[Path]("seedlist", "").orNone

    val l0PeerIdOpts: Opts[PeerId] = Opts
      .option[PeerId]("peer-id", help = "L0 peer Id")
      .orElse(Opts.env[PeerId]("CL_L0_PEER_ID", help = "L0 peer Id"))

    val l0PeerHostOpts: Opts[Host] = Opts
      .option[Host]("l0-ip", help = "L0 peer HTTP host")
      .orElse(Opts.env[Host]("CL_L0_PEER_HTTP_HOST", help = "L0 peer HTTP host"))

    val l0PeerPortOpts: Opts[Port] = Opts
      .option[Port]("startup-port", help = "L0 peer HTTP port")
      .orElse(Opts.env[Port]("CL_L0_PEER_HTTP_PORT", help = "L0 peer HTTP port"))
      .withDefault(port"9000")

    val l0PeerOpts: Opts[L0Peer] = (l0PeerIdOpts, l0PeerHostOpts, l0PeerPortOpts)
      .mapN(L0Peer(_, _, _))

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        seedlistPathOpts,
        CollateralAmountOpts.opts,
        l0PeerOpts
      ).mapN(RunValidator.apply(_, _, _, _, _, _, _, _, _, _))
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}
