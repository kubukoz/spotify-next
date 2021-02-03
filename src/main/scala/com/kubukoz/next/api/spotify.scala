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
import com.kubukoz.next.Spotify.InvalidItem
import io.estatico.newtype.macros.newtype
import cats.data.NonEmptyList

object spotify {
  implicit val circeConfig = Configuration.default.withDiscriminator("type").withSnakeCaseMemberNames

  implicit def optionCodec[A: Codec]: Codec[Option[A]] = Codec.from(Decoder.decodeOption[A], Encoder.encodeOption[A])

  implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder[String].emap(s => Uri.fromString(s).leftMap(failure => failure.details)),
    Encoder[String].contramap(_.renderString)
  )

  sealed trait Item extends Product with Serializable

  object Item {
    final case class track(uri: String, durationMs: Int) extends Item

    implicit val codec: Codec[Item] = deriveConfiguredCodec
  }

  final case class Player[_Ctx, _Item](context: _Ctx, item: _Item, progressMs: Int) {

    def byContext: Player.ByContext[_Item, _Ctx] = Player.ByContext(this)

    def narrowContext[DesiredContext <: _Ctx: ClassTag]: Either[InvalidContext[_Ctx], Player[DesiredContext, _Item]] =
      byContext
        .traverse {
          case desired: DesiredContext => desired.asRight
          case other                   => InvalidContext(other).asLeft
        }
        .map(_.value)

    def narrowItem[DesiredItem <: _Item: ClassTag]: Either[InvalidItem[_Item], Player[_Ctx, DesiredItem]] =
      this.traverse {
        case desired: DesiredItem => desired.asRight
        case other                => InvalidItem(other).asLeft
      }

  }

  object Player {

    @newtype
    final case class ByContext[_Item, _Ctx](value: Player[_Ctx, _Item])

    object ByContext {
      import cats.tagless.syntax.invariantK._
      import com.kubukoz.next.util.traverse._

      def lift[_Item]: Player[*, _Item] ~> ByContext[_Item, *] =
        λ[Player[*, _Item] ~> ByContext[_Item, *]](ByContext(_))

      def unlift[_Item]: ByContext[_Item, *] ~> Player[*, _Item] =
        λ[ByContext[_Item, *] ~> Player[*, _Item]](_.value)

      implicit def traverse[_Item]: NonEmptyTraverse[ByContext[_Item, *]] =
        cats.derived.semi.nonEmptyTraverse[Player[*, _Item]].imapK(lift)(unlift)
    }

    implicit def traverse[_Context]: NonEmptyTraverse[Player[_Context, *]] =
      cats.derived.semi.nonEmptyTraverse[Player[_Context, *]]

    implicit def codec[_Ctx: Codec, _Item: Codec]: Codec[Player[_Ctx, _Item]] = deriveConfiguredCodec
  }

  sealed trait PlayerContext extends Product with Serializable

  final case class PlaylistUri(playlist: String, user: Option[String])

  object PlaylistUri {

    implicit val codec: Codec[PlaylistUri] = Codec.from(
      Decoder[String].emap {
        case s"spotify:user:$userId:playlist:$playlistId" => PlaylistUri(playlistId, userId.some).asRight
        case s"spotify:playlist:$playlistId"              => PlaylistUri(playlistId, none).asRight
        case literallyAnythingElse                        => (literallyAnythingElse + " is not a playlist URI").asLeft
      },
      Encoder[String].contramap { uri =>
        val parts = NonEmptyList.of(
          "playlist" -> uri.playlist
        ) ++ uri.user.tupleLeft("user").toList

        parts
          .map { case (k, v) => show"$k:$v" }
          .mkString_("spotify:", ":", "")
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
