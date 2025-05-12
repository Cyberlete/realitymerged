package org.reality.http.routes

import cats.Monoid
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.reality.dag.domain.block.NETBlock
import org.reality.domain.cell.{L0Cell, L0CellInput}
import org.reality.domain.net.NETService
import org.reality.ext.http4s.AddressVar
import org.reality.kernel._
import org.reality.security.signature.Signed

import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import shapeless._
import shapeless.syntax.singleton._

final case class NetRoutes[F[_]: Async](
  netService: NETService[F],
  mkNetCell: L0Cell.Mk[F],
  monoid: Monoid[Cell[F, StackF, Ω, Ω, Either[CellError, Ω]]]
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/net"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "balances" =>
      netService.getBalances.flatMap {
        case Some((ordinal, balances)) =>
          Ok(("ordinal" ->> ordinal.value.value) :: ("balances" ->> balances) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / AddressVar(address) / "balance" =>
      netService
        .getBalance(address)
        .flatMap {
          case Some((balance, ordinal)) =>
            Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
          case _ => NotFound()
        }

    case GET -> Root / "total-supply" =>
      netService.getTotalSupply.flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / "wallet-count" =>
      netService.getWalletCount.flatMap {
        case Some((wallets, ordinal)) =>
          Ok(("count" ->> wallets) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[NETBlock]]
        .map(L0CellInput.HandleNETL1(_))
        .map(mkNetCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
