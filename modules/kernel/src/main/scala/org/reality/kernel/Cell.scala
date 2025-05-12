package org.reality.kernel

import cats._
import cats.arrow.Arrow
import cats.conversions.all.autoWidenBifunctor
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

//case class Chain[M[_]: Monad, F[_]: Traverse, S, I, O]( d: I,  h: S => M[O],  c: I => S)(chain: I => (Chain[M, F, S, I, O], O)) extends Cell(d, h, c)
class Cell[M[_]: Monad, F[_]: Traverse, S, I, O](val data: I, val hylo: S => M[O], val convert: I => S) extends Hom[I, O] { // TODO: was Topos but we aren't using it yet
  // extends Topos[A, B] {
  def run(): M[O] = hylo(convert(data))

  type CellChain[X, Y] = Cell[M, F, S, I, X] => Cell[M, F, S, I, Y]

  def map[B](f: O => B): Cell[M, F, S, I, B] = new Cell[M, F, S, I, B](data, hylo.map(m => m.map(f)), convert)

  implicit def arrowInstance: Arrow[CellChain] = new Arrow[CellChain[*, *]] {
    override def lift[A, B](f: A => B): CellChain[A, B] = (a: Cell[M, F, S, I, A]) => a.map[B](f)

    override def compose[A, B, C](f: CellChain[B, C], g: CellChain[A, B]): CellChain[A, C] =
      (a: Cell[M, F, S, I, A]) => f.compose(g)(a)

    override def first[A, B, C](fa: CellChain[A, B]): CellChain[(A, C), (B, C)] =
      (a: Cell[M, F, S, I, (A, C)]) => {
        val oldB: Cell[M, F, S, I, A] = new Cell[M, F, S, I, A](a.data, (s: S) => a.run().map(_._1), convert) // todo combine convert?
        val check: Cell[M, F, S, I, B] = fa(oldB)
        val cee: Cell[M, F, S, I, C] = new Cell[M, F, S, I, C](a.data, (s: S) => a.run().map(_._2), convert)
        val joinedRun: M[(B, C)] = check.run().flatMap(bb => cee.run().map(cc => (bb, cc)))

        val combinedHylos: S => M[(B, C)] = cee.hylo.flatMap { cc: M[C] =>
          val ccf = check.hylo.map { bb =>
            val flatCheck = bb.flatMap(bbb => cc.map(ccc => (bbb, ccc)))
            flatCheck
          }
          ccf
        }
        new Cell[M, F, S, I, (B, C)](a.data, hylo = combinedHylos, a.convert) {
          override def run() = joinedRun
        }

      }

  }

//  def toTopos[A, B](runFunc: A => (Topos[A, B], B)) = new Topos[A, B] {val run = runFunc}

}

object Cell {
  case object NullTerminal extends Ω

  def unapply[M[_], F[_], S, A, B](cell: Cell[M, F, S, A, B]): Some[(A, S => M[B])] = Some((cell.data, cell.hylo))

  implicit def toCell[M[_], F[_], A <: Cell[M, F, Ω, Either[CellError, Ω], Ω]](
    a: A
  ): Cell[M, F, Ω, Either[CellError, Ω], Ω] = a.asInstanceOf[Cell[M, F, Ω, Either[CellError, Ω], Ω]]

  implicit def cellMonoid[M[_]: Applicative, F[_]: Applicative](
    implicit M: Monad[M],
    F: Traverse[F]
  ): Monoid[Cell[M, F, Ω, Ω, Either[CellError, Ω]]] =
    new Monoid[Cell[M, F, Ω, Ω, Either[CellError, Ω]]] {

      override def empty: Cell[M, F, Ω, Ω, Either[CellError, Ω]] = {
        val algebra = AlgebraM[M, F, Either[CellError, Ω]] { _ =>
          M.pure(NullTerminal.asInstanceOf[Ω].asRight[CellError])
        }

        val coalgebra = CoalgebraM[M, F, Ω] { _ =>
          M.compose[F].pure(NullTerminal.asInstanceOf[Ω])
        }

        val hyloM = scheme.hyloM(algebra, coalgebra)

        new Cell[M, F, Ω, Ω, Either[CellError, Ω]](
          NullTerminal,
          hyloM,
          _ => NullTerminal
        ) {
          override def run(): M[Either[CellError, Ω]] = M.pure(NullTerminal.asInstanceOf[Ω].asRight[CellError])
        }
      }

      // TODO: A param should be Ω as well to make it possible to combine Cells with different A type
      override def combine(
        x: Cell[M, F, Ω, Ω, Either[CellError, Ω]],
        y: Cell[M, F, Ω, Ω, Either[CellError, Ω]]
      ): Cell[M, F, Ω, Ω, Either[CellError, Ω]] = (x, y) match {
        case (Cell(NullTerminal, _), Cell(NullTerminal, _)) => empty
        case (Cell(NullTerminal, _), yy)                    => yy
        case (xx, Cell(NullTerminal, _))                    => xx
        case (cella @ Cell(a, _), cellb @ Cell(b, _)) =>
          val input: ΩList = a :: b

          val combinedHyloM: Ω => M[Either[CellError, Ω]] = _ =>
            for {
              aOutput <- cella.run()
              bOutput <- cellb.run()
              combinedOutput = aOutput.flatMap(aΩ => bOutput.map(bΩ => aΩ :: bΩ))
            } yield combinedOutput
          new Cell[M, F, Ω, Ω, Either[CellError, Ω]](
            input,
            combinedHyloM,
            _ => input
          )
      }
    }

}

//trait Topos[A, B] extends Hom[A, B] {
//  import Topos._
//  val run: A => (Topos[A, B], B)
//  implicit val rFunctor: Functor[Hom[A, *]] = Topos.rFunctor[A]
//}

//object Topos {
//
//
//  implicit val arrowInstance: Arrow[Topos] = new Arrow[Topos] {
//
//    override def lift[A, B](f: A => B): Topos[A, B] = new Topos[A, B] {
//      val run = lift(f) -> f(_)
//    }
//
//    override def first[A, B, C](fa: Topos[A, B]): Topos[(A, C), (B, C)] =
//      new Topos[(A, C), (B, C)] {
//        val run = {
//          case (a, c) =>
//            val (fa2, b) = fa.run(a)
//            (first(fa2), (b, c))
//        }
//      }
//
//    override def compose[A, B, C](f: Topos[B, C], g: Topos[A, B]): Topos[A, C] = new Topos[A, C] {
//      def morph(a: A) = {
//        val (gg, b) = g.run(a)
//        val (ff, c) = f.run(b)
//        (compose(ff, gg), c)
//      }
//      val run = morph(_)
//    }
//  }
//
//  def merge[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] =
//    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)
//
//  def combineImplicit[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] = {
//    val fa = implicitly[Arrow[F]]
//    fa.lmap[(A, A), (B, C), A](fa.split[A, B, A, C](fab, fac))(a => (a, a))
//  }
//
//  /** similar to the combine function with the addition of running a function on the result of combine
//   * @param fab
//   * @param fac
//   * @param f
//   * @tparam F
//   * @tparam A
//   * @tparam B
//   * @tparam C
//   * @tparam D
//   * @return
//   */
//  def liftA2[F[_, _]: Arrow, A, B, C, D](fab: F[A, B], fac: F[A, C])(f: B => C => D): F[A, D] = {
//    val fa = implicitly[Arrow[F]]
//    merge[F, A, B, C](fab, fac).rmap { case (b, c) => f(b)(c) }
//  }
//
//  /** FunctionK but with a CoYoneda decomposition. todo use this and reduce over Day as lFunctor the resolves nat transforms
//   *
//   * @param transformation
//   * @tparam F
//   * @tparam G
//   * @return
//   */
//  //  implicit def inject[F[_], G[_]](transformation: F ~> G) =
//  //    new (FreeF[F, *] ~> FreeF[G, *]) { // transformation of free algebras
//  //      def apply[A](fa: FreeF[F, A]): FreeF[G, A] =
//  //        fa.mapK[Coyoneda[G, *]](new (Coyoneda[F, *] ~> Coyoneda[G, *]) {
//  //          def apply[B](fb: Coyoneda[F, B]): Coyoneda[G, B] =
//  //            fb.mapK(transformation)
//  //        })
//  //    }
//
//  //  implicit val repr = new Representable[Contravariant] {
//  //    override def F: Functor[Contravariant] = ???
//  //
//  //    override type Representation = this.type
//  //
//  //    override def index[A](f: Contravariant[A]): this.type => A = ???
//  //    // https://ncatlab.org/nlab/show/2-sheaf
//  //    // https://ncatlab.org/nlab/show/indexed+category
//  //
//  //    /** todo use Enrichment to maintain order
//  //      *
//  //      * @param f
//  //      * @tparam A
//  //      * @return
//  //      */
//  //    override def tabulate[A](f: this.type => A): Contravariant[A] = ???
//  //  }
//
//  //  val representation: Representable[Contravariant] = Representable(repr)
//
//  //  implicit def monoidK[A]: MonoidK[Contravariant] = new MonoidK[Contravariant] {
//  //    override def empty[A]: Contravariant[A] = ???
//  //
//  //    override def combineK[A](x: Contravariant[A], y: Contravariant[A]): Contravariant[A] = ???
//  //  }
//  implicit def rFunctor[A]: Functor[Hom[A, *]] = new Functor[Hom[A, *]] {
//    override def map[B, C](fa: Hom[A, B])(f: B => C): Hom[A, C] =
//      fa match {
////        case EmptyCell()            => EmptyCell()
////        case TypedCoCellConsF(a, b) => TypedCoCellConsF(a, f(b))
//        case _                      => new Hom[A, *]{}
//      }
//  }
//}
