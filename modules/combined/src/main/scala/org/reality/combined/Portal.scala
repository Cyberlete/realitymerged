package org.reality.combined

import cats.arrow.Arrow
import cats.effect._
import cats.implicits._
import cats.{Applicative, Comonad, Traverse}

import scala.util.Either

import org.reality.BuildInfo
import org.reality.combined.CoCell._
import org.reality.combined.EnvUtil.{envVarOrError, extractFlagValueReturnFiltered, stripAndKeepValues}
import org.reality.combined.examples.CoData
import org.reality.dag.l1.modules.EmptyCellObj
import org.reality.dag.l1.{MkStateChannel, WasmExecutionParams}
import org.reality.kernel._
import org.reality.modules.HttpApi
import org.reality.schema.cluster.ClusterId
import org.reality.sdk.app.{NodeInternals, SDK}
import org.reality.sdk.cli.CliMethod

import com.comcast.ip4s.{Host, Port}
import com.monovore.decline.{Command, Help, Opts}
import fs2.Stream
import fs2.concurrent.SignallingRef
import higherkindness.droste.data.Fix
import org.http4s.HttpRoutes
import org.slf4j.LoggerFactory

abstract class Portal extends IOApp {
  private val logger = LoggerFactory.getLogger(this.getClass)
  val cellProgram: List[String] => IO[Seq[Context[CoCell]]]

  override def run(args: List[String]): IO[ExitCode] = {

    println("args - " + args)
    val isNodeApi: Boolean = args.contains("--node-api")
    val nodeApiSetup: IO[Option[(Host, Port)]] = if (isNodeApi) {
      for {
        apiHostStr <- envVarOrError("API_HOST")
        apiPortStr <- envVarOrError("API_PORT")
        apiHost <- Host.fromString(apiHostStr) match {
          case Some(host) => IO.pure(host)
          case None       => IO.raiseError(new Exception(s"Invalid API_HOST: $apiHostStr"))
        }
        apiPort <- Port.fromString(apiPortStr) match {
          case Some(port) => IO.pure(port)
          case None       => IO.raiseError(new Exception(s"Invalid API_PORT: $apiPortStr"))
        }
      } yield Some((apiHost, apiPort))
    } else {
      IO.pure(None)
    }

    println("val program")
    implicit val program: IO[ExitCode] = for {
      useResources: Seq[Context[CoCell]] <- cellProgram(args)
      nodeResources: Seq[Resource[IO, NodeInternals]] = useResources.map(_.resource._1)
//      nodeInternals: Seq[NodeInternals] <- nodeResources.map(_.use(IO.pure)).sequence
      nodeApiConfig <- nodeApiSetup

      result <- nodeApiConfig match {
//        case Some((apiHost, apiPort)) =>
//          for {
//            api <- NodeApi.make(nodeInternals.toList)
//            httpApi = CombinedApi(api)
//            exitCode <- MkHttpServer[IO]
//              .newEmber(ServerName("api"), HttpServerConfig(apiHost, apiPort, 1.second), httpApi.app)
//              .useForever
//              .as(ExitCode.Success)
//          } yield exitCode
        case _ =>
          val runtime = nodeResources.reduce((firstResource, NextResource) => firstResource >> NextResource) // l0Resource >> l1Resource
          for {
            code <- Stream.eval(runtime.useForever).compile.drain.as(ExitCode.Success)
          } yield code
      }
//      result: ExitCode <- Stream.eval(runtime.useForever).compile.drain.as(ExitCode.Success)

      _ <- IO.pure(logger.info(s"Portal program executed: ${result.toString}")).start

    } yield result

    program.handleErrorWith { error =>
      implicit val traverse: Traverse[StackF] = StackF.traverse
      implicit val applicative: Applicative[StackF] = StackF.applicative
//
      logger.error("An error occurred", error)
      IO.pure(ExitCode.Error)
    }

  }
}

case class CoCellEndpoints[F[_]: Async](openRoutesList: List[HttpRoutes[F]] = Nil, p2pRoutes: List[HttpRoutes[F]] = Nil)

object CoCell {
  import cats.Monoid

  implicit def toCoHom(thisCoCell: CoCell): CoHomF[Ω] =
    new CoHomF[Ω](thisCoCell) {
      override val coCell: CoCell = thisCoCell
    }
//  val coCellArrow = new Arrow[Transformation] {
//    override def lift[A, B](f: A => B): Transformation[A, B] = (a: CoHomF[A]) => a.map[B](f)
//    override def compose[A, B, C](f: Transformation[B, C], g: Transformation[A, B]): Transformation[A, C] = (a: CoHomF[A]) =>
//      f.compose(g)(a)
//    override def first[A, B, C](fa: Transformation[A, B]): Transformation[(A, C), (B, C)] =
//      (a: CoHomF[(A, C)]) =>
//        new CoHomF[(B, C)]((fa(a.map(_._1)).start, a.map(_._2).start)) {
////          override val coCell = new CoCell {
////            val value = (fa(a.map(_._1)).start, a.map(_._2).start)
////          }
//        }
//  }

  type Transformation[X, Y] = CoHomF[X] => CoHomF[Y]
  val coCellArrow = new Arrow[Transformation] {
    override def lift[A, B](f: A => B): Transformation[A, B] = (a: CoHomF[A]) => a.map[B](f)
    override def compose[A, B, C](f: Transformation[B, C], g: Transformation[A, B]): Transformation[A, C] = (a: CoHomF[A]) =>
      a match { // todo need implicit for TypedCoCellLast, use in fixed
        case a @ TypedCoCellLast(l)    => TypedCoCellCons[C, A](f.compose(g)(a).start, a.start)
        case b @ TypedCoCellCons(x, y) => TypedCoCellCons[C, A](f.compose(g)(b).start, b.start)
        case c: CoHomF[A]              => TypedCoCellLast[C, A](f.compose(g)(c).start)

      }
    override def first[A, B, C](
      fa: Transformation[A, B]
    ): Transformation[(A, C), (B, C)] = (a: CoHomF[(A, C)]) =>
      TypedCoCellCons[(B, C), (A, C)]((fa(a.map(_._1)).start, a.map(_._2).start), a.start)
  }

  case class Context[A](val cell: A, resource: (Resource[IO, CoCellInternals], SignallingRef[IO, Unit])) extends CoHomF[A](cell) {
//    override val coCell = new CoCell {
//      val value = cell
//    }
  }

  case class thing(value: CoCell, tail: CoCell) extends TypedCoCellF[CoCell, CoCell](value)
  def fixed(coCell: CoCell): Fix[Hom[CoCell, *]] = Fix(TypedCoCellCons(coCell, Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])))

  // todo use hlist to pattern match?
  def fixed2[Z](coCell: CoHomF[Z]): Fix[Hom[CoCell, *]] = coCell match {
    case a: TypedCoCellLast[_, _] => Fix(TypedCoCellCons(a, Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])))
    case b: TypedCoCellCons[_, _] => Fix(TypedCoCellCons(b.coCell, fixed2(b.coCell.ohmToCoHom(b.coCell))))
    case _ =>
      println("Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])")
      Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])
  }

//  def fixed3[Z](coCell: Class[_ <: CoHomF[Z]]): Fix[Hom[CoCell, *]] = coCell match {
//    case a if (coCell == classOf[TypedCoCellLast[_, _]]) => Fix(TypedCoCellCons(coCell, Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])))
//  }

//  -//  def fixed(coCell: CoCell) = coCell match {
//    -//        case a@TypedCoCellLast(l) => Fix(TypedCoCellCons(coCell, Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])))
//  -//        case b: TypedCoCellCons[CoCell, CoCell] => Fix(TypedCoCellCons(b.start, fixed(b.tail): Fix[Hom[CoCell, *]]))
//    -//    case c: CoCell => Fix(TypedCoCellCons(coCell, Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])))
//  -//    case _ => Fix(EmptyCell(): Hom[CoCell, Fix[Hom[CoCell, *]]])
//    -//  }

  implicit def coHomToListCoCell[Z](ch: CoHomF[Z]): Seq[CoCell] =
    Hom.toScalaList(fixed2(ch))

//  implicit def testCoHomMappings[Z](ch: CoHomF[Z] => CoHomF[Z]) = ch match {
//    case (a: CoHomF[Z]) => (b: CoHomF[Z])
//  }

  implicit def toList(coCell: CoCell): List[CoCell] = coCell :: Nil
  implicit def fromList(coCell: List[CoCell]): CoCell = coCell.head

  implicit class Day(coCells: List[CoCell])

  implicit val coCellMonoid: Monoid[CoCell] = new Monoid[CoCell] {
    override def empty: CoCell = new CoCell {}
    override def combine(x: CoCell, y: CoCell): CoCell = new CoCell {
      val t = y.ohmToCoHom
      override def mkCell[F[_]: Async]: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = t(x.coData).coCell.mkCell
    }
  }

  val day: Monoid[List[CoCell]] = new Monoid[List[CoCell]] {
    def empty: List[CoCell] = Nil

    def combine(x: List[CoCell], y: List[CoCell]): List[CoCell] =
      (x, y) match {
        case (Nil, Nil) => Nil
        case (c, Nil)   => c
        case (Nil, d)   => d
//        case (c, d) if c.head.isInstanceOf[CoCellCoHom] && d.head.isInstanceOf[CoCellCoHom] =>
//          (Hom.toScalaList(CoCell.fixed(c.head)) ++ Hom.toScalaList(CoCell.fixed(c.head))).toList
        case (c: List[CoCell], d: List[CoCell]) => c ++ d
      }
  }
//  val t = Fix[ListF[Int, *]](NilF)

  def join(me: CoCell, otherCell: CoCell): CoCell = new CoCell { // todo pass in endpoints here
    override def getEndpoints[F[_]: Async] = me.getEndpoints ++ otherCell.getEndpoints
    override val stateChannels: List[MkStateChannel] = me.stateChannels ++ otherCell.stateChannels
//    override def mkResources: (CliMethod, SDK[IO]) => Resource[IO, HttpApi[IO]] = L0HttpApiObj.mkResources

  }

  implicit def toList: Seq[CoCell.type] = this :: Nil

  implicit def getCoCellByName(program: Seq[Context[CoCell]])(name: String): Option[Context[CoCell]] = program.find(_.cell.name == name)

//  def compile(coCells: List[CoCell]): List[CoCell] = coCells.flatMap {
//    case thing => thing.head <~> thing.tail
//  }
}

case class EmptyCell() extends Hom[Nothing, Nothing]

sealed abstract class TypedCoCellF[A, B](value: A) extends CoHomF[A](value) with Hom[A, B] //todo overflow here

case class TypedCoCellCons[A, B](value: A, tail: B) extends TypedCoCellF[A, B](value)

case class TypedCoCellLast[A, B](value: A) extends TypedCoCellF[A, B](value) {}

sealed abstract class CoHomF[Z](val start: Z) extends CoCell {
  def map[B](f: Z => B): CoHomF[B] = new CoHomF[B](f(start)) {}
}

/** todo use L0 for default
  * @param args
  * @param prefix
  * @param commandStr
  * @param opts
  * @param commandName
  * @param commandDesc
  */
case class StartUp(
  args: List[String]
) {
  val (l1Command, l1Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l1"), "--command", "run-initial-validator")
  val l1ArgsWithCommand: List[String] = l1Command :: l1Args

  val (l0Command, l0Args) = extractFlagValueReturnFiltered(stripAndKeepValues(args, "--l0"), "--command", "run-genesis")
  val l0ArgsWithCommand: List[String] = l0Command :: l0Args

  //    logger.info(s"L0 Command: $l0Command, L0 Args: $l0Args")

  val l0method: Either[Help, org.reality.cli.method.Run] =
    Command("L0Command", "Run L0")(org.reality.cli.method.opts).parse(l0ArgsWithCommand)
  val l1method: Either[Help, org.reality.dag.l1.cli.Run] =
    Command("L1Command", "Run L1")(org.reality.dag.l1.cli.method.opts).parse(l1ArgsWithCommand)
}

/** Each CoCell child should extend its terminal object, compose should throw error for wrong type object
  */
trait CoCell extends Ω {
  val name: String = this.getClass.getName

  val coCell: CoCell = this
  val innerCoCell = this
  val coData: CoData = CoData(Nil, Nil, Nil) // todo put here
  val stateChannels: List[MkStateChannel] = Nil
  def mkResources[A <: CliMethod]: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] = L0HttpApiObj.mkResources

  def newCoCellWithCoData(newCoData: CoData): CoCell = new CoCell { override val coData = newCoData }
  def mkCell[F[_]: Async]: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = EmptyCellObj.mkCell

  implicit def ohmToCoHom: Ω => CoHomF[Ω] = (ohm: Ω) =>
    new CoHomF[Ω](ohm) {
      override val coCell: CoCell = innerCoCell
//      override val coCell = new CoCell {
//        val value = ohm
//      }
    }

  // todo use this in <~>? then lift via >>>? Rather join two CoHom with <~>, compose joined CoHom with >>>
  implicit def mixHomCoHom[Z]: CoHomF[Z] => CoHomF[Z] = (coCell: CoHomF[Z]) =>
    new CoHomF[Z](coCell.start) {
      override val coCell: CoCell = new CoCell {
        override def mkCell[F[_]: Async]: Ω => Cell[F, StackF, Ω, Ω, Either[CellError, Ω]] = coCell.mkCell >>> this.mkCell

        override def setupCombined[A <: CliMethod](
          sct: List[MkStateChannel],
          coCell: CoCell,
          startup: Either[Help, A],
          theseOpts: Opts[A],
          mkResource: (A, SDK[IO]) => Resource[IO, HttpApi[IO]],
          wasmPrograms: List[WasmExecutionParams[_, _]] = List.empty
        ): IO[Context[CoCell]] =
          coCell.setupCombined(sct, coCell, startup, theseOpts, mkResource) >> this
            .setupCombined(sct, coCell, startup, theseOpts, mkResource)
      }
    }
  def coFlatMap[B](f: CoHomF[Ω] => B): CoHomF[B] = coMonad.coflatMap(this)(f)

  val coMonad: Comonad[CoHomF] = new cats.Comonad[CoHomF] {
    override def extract[A](x: CoHomF[A]): A = x.start

    override def coflatMap[A, B](fa: CoHomF[A])(f: CoHomF[A] => B): CoHomF[B] = new CoHomF[B](f(fa)) {
//      override val coCell = new CoCell {
//        val value = start
//      }
    }

    override def map[A, B](fa: CoHomF[A])(f: A => B): CoHomF[B] = new CoHomF[B](f(fa.start)) {
//      override val coCell = new CoCell {
//        val value = f(fa.start)
//      }
    }
  }

  def argsToStartUp(args: List[String]): StartUp = StartUp(args)

  def join[F[_]: Async](otherCell: CoCell): CoCell = CoCell.join(this, otherCell)

  def setup(args: List[String]): IO[CoCell.Context[CoCell]] =
    setupCombined[org.reality.cli.method.Run](stateChannels, this, argsToStartUp(args).l0method, org.reality.cli.method.opts)
//  >>
//      setupCombined(stateChannels, this, argsToStartUp(args).l1method)

  def setupCombined[A <: CliMethod](
    sct: List[MkStateChannel],
    coCell: CoCell,
    startup: Either[Help, A],
    theseOpts: Opts[A],
    mkResource: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] = this.mkResources(_: A, _: SDK[IO]),
    localWasmPrograms: List[WasmExecutionParams[_, _]] = List.empty
  ): IO[Context[CoCell]] =
    startup match {
      case Right(config) =>
        println("config - " + config)
        val resource: IO[(Resource[IO, CoCellInternals], SignallingRef[IO, Unit])] =
          Bootstrap.combinedBootstrap[A](
            name = "CoCell",
            header = "CoCell",
            version = BuildInfo.version,
            clusterId = ClusterId(java.util.UUID.fromString("17e78993-37ea-4539-a4f3-039068ea1e92")),
            run = new CoCellMain[A] {
              val opts: Opts[A] = theseOpts
              val sc: Option[MkStateChannel] = sct.headOption
              val coCellInst: CoCell = coCell
              val mkApi: (A, SDK[IO]) => Resource[IO, HttpApi[IO]] = mkResource
              val wasmPrograms: List[WasmExecutionParams[_, _]] = localWasmPrograms
            }.run,
            method = config
          )
        resource.map(r => Context(coCell, r))
      case Left(help) =>
        IO.raiseError(new Exception(help.toString()))
    }

  def getEndpoints[F[_]: Async]: List[(List[HttpRoutes[F]], List[HttpRoutes[F]])] = List((Nil, Nil)) // todo get from codata

//  implicit def <~>(other: List[CoCell]) = CoCell.day.combine(this, other)
  def <~>[Z](other: CoHomF[Z]): CoHomF[Z] = this.mixHomCoHom(other)

}
