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
import cats.data.NonEmptyList
import monocle.PLens
import monocle.macros.PLenses

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

  @PLenses final case class Player[_Ctx, _Item](context: _Ctx, item: _Item, progressMs: Int) {
    private def itemLens[NewItem]: PLens[Player[_Ctx, _Item], Player[_Ctx, NewItem], _Item, NewItem] = Player.item
    private def contextLens[NewContext]: PLens[Player[_Ctx, _Item], Player[NewContext, _Item], _Ctx, NewContext] = Player.context

    // It may not seem like it, but this is traverse.
    def unwrapContext[F[_], Ctx2](
      implicit ev: _Ctx <:< F[Ctx2],
      fFunctor: Functor[F]
    ): F[Player[Ctx2, _Item]] =
      contextLens[Ctx2]
        .modifyF[F](ev.apply)(this)

    def unwrapItem[F[_], Item2](
      implicit ev: _Item <:< F[Item2],
      fFunctor: Functor[F]
    ): F[Player[_Ctx, Item2]] =
      itemLens[Item2]
        .modifyF[F](ev.apply)(this)

    def narrowContext[DesiredContext <: _Ctx: ClassTag]: Either[InvalidContext[_Ctx], Player[DesiredContext, _Item]] =
      contextLens[DesiredContext]
        .modifyF {
          case desired: DesiredContext => desired.asRight
          case other                   => InvalidContext(other).asLeft
        }(this)

    def narrowItem[DesiredItem <: _Item: ClassTag]: Either[InvalidItem[_Item], Player[_Ctx, DesiredItem]] =
      itemLens[DesiredItem].modifyF {
        case desired: DesiredItem => desired.asRight
        case other                => InvalidItem(other).asLeft
      }(this)

  }

  object Player {
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

  final case class TokenResponse(accessToken: String, refreshToken: String)

  object TokenResponse {
    implicit val codec: Codec[TokenResponse] = deriveConfiguredCodec
  }

  final case class RefreshedTokenResponse(accessToken: String)

  object RefreshedTokenResponse {
    implicit val codec: Codec[RefreshedTokenResponse] = deriveConfiguredCodec
  }

}
