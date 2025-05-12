package org.reality.sdk.http.p2p.clients

import cats.effect.Async

import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.schema.SnapshotOrdinal
import org.reality.schema.address.Address
import org.reality.sdk.http.p2p.PeerResponse
import org.reality.sdk.http.p2p.PeerResponse.PeerResponse
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed

import io.circe.Decoder
import io.circe.magnolia.derivation.decoder.semiauto._
import io.circe.refined._
import org.http4s.client.Client

case class DeployAppTransactionInfo(
  source: Address,
  appName: String,
  appVersion: String,
  appDescription: String,
  appDownloadURL: String,
  binaryHash: String
)

trait L0GlobalSnapshotClient[F[_]] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]]
  def getLatest: PeerResponse[F, (Signed[GlobalSnapshot], GlobalSnapshotInfo)]
  def getApp(appIdentifier: String): PeerResponse[F, Option[DeployAppTransactionInfo]]
}

object L0GlobalSnapshotClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F]): L0GlobalSnapshotClient[F] =
    new L0GlobalSnapshotClient[F] {

      private val urlPrefix = "global-snapshots"
      def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        implicit val decoder: Decoder[SnapshotOrdinal] = deriveMagnoliaDecoder[SnapshotOrdinal]

        PeerResponse[F, SnapshotOrdinal]("global-snapshots/latest/ordinal")(client)
      }

      def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]] = {
        import org.reality.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[GlobalSnapshot]](s"global-snapshots/${ordinal.value.value}")(client)
      }

      def getLatest: PeerResponse[F, (Signed[GlobalSnapshot], GlobalSnapshotInfo)] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

        PeerResponse[F, (Signed[GlobalSnapshot], GlobalSnapshotInfo)](s"$urlPrefix/latest/combined")(client)
      }

      def getApp(appIdentifier: String): PeerResponse[F, Option[DeployAppTransactionInfo]] = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
        import io.circe.generic.auto._
        implicit val decoder: Decoder[Option[DeployAppTransactionInfo]] = Decoder.decodeOption[DeployAppTransactionInfo]

        PeerResponse[F, Option[DeployAppTransactionInfo]](s"global-snapshots/app-data/${appIdentifier}")(client)
      }
    }
}
