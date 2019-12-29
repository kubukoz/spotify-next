package com.kubukoz.next.api

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.Codec
import org.http4s.Uri
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import org.http4s.EntityDecoder

object spotify {
  implicit val circeConfig = Configuration.default.withDiscriminator("type")

  implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder[String].emap(s => Uri.fromString(s).leftMap(failure => failure.details)),
    Encoder[String].contramap(_.renderString)
  )
  final case class Player(context: PlayerContext)

  object Player {
    implicit val codec: Codec[Player] = deriveConfiguredCodec
  }

  sealed trait PlayerContext extends Product with Serializable

  object PlayerContext {
    final case class playlist(href: Uri) extends PlayerContext
    final case class album(href: Uri) extends PlayerContext
    final case class artist(href: Uri) extends PlayerContext

    implicit val codec: Codec[PlayerContext] = deriveConfiguredCodec
  }

  sealed trait Anything extends Product with Serializable
  case object Void extends Anything
  val anything: Anything = Void

  object Anything {
    implicit def entityCodec[F[_]: Sync]: EntityDecoder[F, Anything] = EntityDecoder.void[F].map(_ => anything)
  }

}
