package com.kubukoz.next.api

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.Codec
import org.http4s.Uri
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.EntityDecoder
import scala.reflect.ClassTag
import com.kubukoz.next.Spotify.InvalidContext

object spotify {
  implicit val circeConfig = Configuration.default.withDiscriminator("type")

  implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder[String].emap(s => Uri.fromString(s).leftMap(failure => failure.details)),
    Encoder[String].contramap(_.renderString)
  )

  final case class Player[Ctx <: PlayerContext](context: Ctx) {

    def narrowContext[DesiredContext <: Ctx: ClassTag]: Either[InvalidContext, Player[DesiredContext]] = context match {
      case desired: DesiredContext => copy(context = desired).asRight
      case other                   => InvalidContext(other).asLeft

    }
  }

  object Player {
    implicit def codec[Ctx <: PlayerContext: Codec]: Codec[Player[Ctx]] = deriveConfiguredCodec
  }

  sealed trait PlayerContext extends Product with Serializable

  final case class PlaylistUri(user: String, playlist: String)

  object PlaylistUri {

    implicit val codec: Codec[PlaylistUri] = Codec.from(
      Decoder[String].emap {
        case s"spotify:user:$userId:playlist:$playlistId" => PlaylistUri(userId, playlistId).asRight
        case literallyAnythingElse                        => (literallyAnythingElse + " is not a playlist URI").asLeft
      },
      Encoder[String].contramap { uri =>
        show"spotify:user:${uri.user}:playlist:${uri.playlist}"
      }
    )
  }

  object PlayerContext {
    final case class playlist(href: Uri, uri: PlaylistUri) extends PlayerContext
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
