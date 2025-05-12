package org.reality.combined

import cats.arrow.Arrow
import cats.data.NonEmptyList
import cats.implicits._
import cats.kernel.Eq
import cats.{Functor, _}

import org.reality.combined.CoCell._
import org.reality.combined.Hom.{basisHomMonoid, drosteTraverseForHom}
import org.reality.combined.examples.{CoData, Dex}
import org.reality.kernel.Hom

import higherkindness.droste._
import higherkindness.droste.data.Fix
import higherkindness.droste.data.list.{ConsF, ListF, NilF}
import higherkindness.droste.syntax.all.∘
import higherkindness.droste.util.DefaultTraverse
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.{Arbitrary, Properties}

object Hom {
  import cats.syntax.applicative._
  import cats.syntax.functor._

  def empty[A] = new Hom[A, A] {}

  /** For traversing along Enrichment
    *
    * @tparam A
    *   Fixed type
    * @return
    */
  implicit def drosteTraverseForHom[A]: Traverse[Hom[A, *]] =
    new DefaultTraverse[Hom[A, *]] {
      def traverse[F[_]: Applicative, B, C](
        fb: Hom[A, B]
      )(f: B => F[C]): F[Hom[A, C]] =
        fb match {
          case TypedCoCellCons(head, tail) => f(tail).map(TypedCoCellCons(head, _))
          case TypedCoCellLast(value)      => (TypedCoCellLast(value): Hom[A, C]).pure[F]
          case EmptyCell()                 => (EmptyCell(): Hom[A, C]).pure[F]
          case _                           => (EmptyCell(): Hom[A, C]).pure[F]
        }
    }

  /** @param list
    * @param ev
    * @tparam A
    * @tparam PatR
    * @return
    */
  def toScalaList[A, PatR[_[_]]](
    list: PatR[Hom[A, *]]
  )(implicit ev: Project[Hom[A, *], PatR[Hom[A, *]]]): List[A] =
    scheme.cata(toScalaListAlgebra[A]).apply(list)

  /** @tparam A
    * @return
    */
  def toScalaListAlgebra[A]: Algebra[Hom[A, *], List[A]] = Algebra {
    case TypedCoCellCons(head, tail) => head :: tail
    case TypedCoCellLast(thing)      => thing :: Nil
    case _                           => Nil
  }

  implicit def basisHomMonoid[T, A](
    implicit T: Basis[Hom[A, *], T]
  ): Monoid[T] =
    new Monoid[T] {
      def empty = T.algebra(EmptyCell())

      def combine(f1: T, f2: T): T =
        scheme
          .cata(Algebra[Hom[A, *], T] {
            case EmptyCell() => f2
            case cons        => T.algebra(cons)
          })
          .apply(f1)
    }

  /** @param eh
    * @tparam A
    * @return
    */
  implicit def drosteDelayEqHom[A](implicit eh: Eq[A]): Delay[Eq, Hom[A, *]] =
    λ[Eq ~> (Eq ∘ Hom[A, *])#λ](et =>
      Eq.instance((x, y) =>
        x match {
          case TypedCoCellCons(hx, tx) =>
            y match {
              case TypedCoCellCons(hy, ty) => eh.eqv(hx, hy) && et.eqv(tx, ty)
              case EmptyCell()             => false
              case _                       => false
            }
          case EmptyCell() =>
            y match {
              case EmptyCell() => true
              case _           => false
            }
          case _ => false
        }
      )
    )
}

/** @param coData
  * @param coCell
  */
case class CoTopos(coData: CoData, coCell: CoCell)

/** @param run
  * @tparam A
  * @tparam B
  */
case class Topos[A, B](val run: A => (Topos[A, B], B)) extends Hom[A, B] {
  implicit val rFunctor: Functor[Hom[A, *]] = Topos.rFunctor[A]
}

object Topos {
  import CoData.monoid
  implicit val arrowInstance: Arrow[Topos] = new Arrow[Topos] {
    override def lift[A, B](f: A => B): Topos[A, B] = Topos[A, B](lift(f) -> f(_))
    override def first[A, B, C](fa: Topos[A, B]): Topos[(A, C), (B, C)] =
      Topos[(A, C), (B, C)] {
        case (a, c) =>
          val (fa2, b) = fa.run(a)
          (first(fa2), (b, c))
      }
    override def compose[A, B, C](f: Topos[B, C], g: Topos[A, B]): Topos[A, C] = {
      def morph(a: A) = {
        val (gg, b) = g.run(a)
        val (ff, c) = f.run(b)
        (compose(ff, gg), c)
      }
      Topos[A, C](morph(_))
    }
  }

  def runList[A, B](ff: Topos[A, B], as: List[A]): List[B] = as match {
    case h :: t =>
      val (ff2, b) = ff.run(h)
      b :: runList(ff2, t)
    case _ => List()
  }

  def accum[A, B](b: B)(f: (A, B) => B): Topos[A, B] = Topos[A, B] { a: A =>
    val b2 = f(a, b)
    val run = (_: A) => (this, b2)
    (accum(b2)(f), b2)
  }
  def braid: Topos[CoCell, CoTopos] = combine(mergeCoData, mergeCells) >>> Arrow[Topos].lift {
    case (x, y) => CoTopos(x, y)
  }

  def run(coCells: List[CoCell]) = runList(braid, coCells)

  def sum[Z: Monoid]: Topos[Z, Z] = accum(Monoid[Z].empty)(_ |+| _)

  def count[Z]: Topos[Z, Int] = arrowInstance.lift((_: Z) => 1) >>> sum

  // todo override def to pattern match on proper cocell to combine with
  def mergeCoData: Topos[CoCell, CoData] = accum(Monoid[CoData].empty) {
    // todo use rules to determine if they match? x.coCell.coData.rules.intersect(y.rules)
    case (x: Dex, y) =>
      println(s"(x: Dex, y: Dex) x $x y $y")
      println(s" x.coData.copy(parlays = y.parlays) ${x.coData.copy(parlays = y.parlays)}")
      x.coData // this adds nothing
//      x.coData.copy(parlays = y.parlays)
//    case (x: Dex, y: CoCell) =>
//      println(s"(x: Dex, y: CoCell) x $x y $y")
//
//      monoid.empty
//    case (x: CoCell, y: Dex) =>
//      println(s"(x: CoCell, y: Dex) x $x y $y")
//      monoid.combine(x.coData, y)
    case (x: CoCell, y) =>
      println(s"(x: CoCell, y: CoCell) x $x y $y")
      monoid.combine(x.coData, y)
//    case _ =>
//      println(s"_ monoid.empty")
//      monoid.empty
  }

  def mergeCells: Topos[CoCell, CoCell] = accum(Monoid[CoCell].empty)(coCellMonoid.combine)

  def combine[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] =
    Arrow[F].lift((a: A) => (a, a)) >>> (fab *** fac)

  def combineImplicit[F[_, _]: Arrow, A, B, C](fab: F[A, B], fac: F[A, C]): F[A, (B, C)] = {
    val fa = implicitly[Arrow[F]]
    fa.lmap[(A, A), (B, C), A](fa.split[A, B, A, C](fab, fac))(a => (a, a))
  }

  /** similar to the combine function with the addition of running a function on the result of combine
    *
    * @param fab
    * @param fac
    * @param f
    * @tparam F
    * @tparam A
    * @tparam B
    * @tparam C
    * @tparam D
    * @return
    */
  def liftA2[F[_, _]: Arrow, A, B, C, D](fab: F[A, B], fac: F[A, C])(f: B => C => D): F[A, D] = {
    val fa = implicitly[Arrow[F]]
    combine[F, A, B, C](fab, fac).rmap { case (b, c) => f(b)(c) }
  }

  implicit def rFunctor[A]: Functor[Hom[A, *]] = new Functor[Hom[A, *]] {
    override def map[B, C](fa: Hom[A, B])(f: B => C): Hom[A, C] =
      fa match {
        case EmptyCell()           => EmptyCell()
        case TypedCoCellCons(a, b) => TypedCoCellCons(a, f(b))
        case _                     => EmptyCell()
      }
  }
}

//todo put in test
object TransverseTest extends Properties("TransverseTest") {
  property("Fix Hom -> List") = {
    val fixed: Fix[Hom[Int, *]] =
      Fix(TypedCoCellCons(1, Fix(TypedCoCellCons(2, Fix(TypedCoCellCons(3, Fix(EmptyCell(): Hom[Int, Fix[Hom[Int, *]]])))))))
    Hom.toScalaList(fixed) ?= 1 :: 2 :: 3 :: Nil
  }
}

object MutuallyRecursive {
//  import Topos.Contravariant
  def transListToHom[A]: TransM[Option, ListF[A, *], Hom[A, *], Fix[ListF[A, *]]] = TransM {
    case ConsF(head, tail) =>
      Fix.un(tail) match {
        case NilF => TypedCoCellLast(head).some
        case _    => TypedCoCellCons(head, tail).some
      }
    case NilF => None
  }

  def transCoCellToHom[A]: TransM[Option, ListF[A, *], Hom[A, *], Fix[ListF[A, *]]] = TransM {
    case ConsF(head, tail) =>
      Fix.un(tail) match {
        case NilF => TypedCoCellLast(head).some
        case _    => TypedCoCellCons(head, tail).some
      }
    case NilF => None
  }

  def toHomF[A]: Fix[ListF[A, *]] => Option[Fix[Hom[A, *]]] =
    scheme.anaM(transListToHom[A].coalgebra)

  def transHomToList[A]: Trans[Hom[A, *], ListF[A, *], Fix[ListF[A, *]]] = Trans {
    case TypedCoCellCons(head, tail) => ConsF(head, tail)
    case TypedCoCellLast(last)       => ConsF(last, Fix[ListF[A, *]](NilF))
    case _                           => NilF
  }

  def transCoCellToList[A]: Trans[Hom[A, *], ListF[A, *], Fix[ListF[A, *]]] = Trans {
    case TypedCoCellCons(head, tail) => ConsF(head, tail)
    case TypedCoCellLast(last)       => ConsF(last, Fix[ListF[A, *]](NilF))
    case _                           => NilF
  }

  def fromHomF[A]: Fix[Hom[A, *]] => Fix[ListF[A, *]] =
    scheme.cata(transHomToList[A].algebra)

  implicit def arbitraryNEL[A: Arbitrary]: Arbitrary[NonEmptyList[A]] =
    Arbitrary(for {
      head <- arbitrary[A]
      tail <- arbitrary[List[A]]
    } yield NonEmptyList.of(head, tail: _*))
}
