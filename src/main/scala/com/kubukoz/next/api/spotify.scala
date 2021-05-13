package com.kubukoz.next.api

import scala.reflect.ClassTag

import cats.data.NonEmptyList
import com.kubukoz.next.Spotify.InvalidContext
import com.kubukoz.next.Spotify.InvalidItem
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.syntax._
import monocle.PLens
import monocle.macros.GenLens
import org.http4s.EntityDecoder
import org.http4s.Uri
import cats.Functor
import cats.implicits._
import cats.effect.Concurrent

object spotify {
  private def asJsonWithType[T: Encoder.AsObject](t: T, tpe: String) = t.asJsonObject.add("type", tpe.asJson).asJson

  private def byTypeDecoder[T](withType: (String, Decoder[_ <: T])*): Decoder[T] = Decoder[String].at("type").flatMap { tpe =>
    withType
      .collectFirst { case (`tpe`, decoder) =>
        decoder
      }
      .toRight(tpe)
      .fold(unknown => Decoder.failedWithMessage[T](s"Unknown type: $unknown"), _.widen[T])
  }

  implicit def optionCodec[A: Codec]: Codec[Option[A]] = Codec.from(Decoder.decodeOption[A], Encoder.encodeOption[A])

  implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder[String].emap(s => Uri.fromString(s).leftMap(failure => failure.details)),
    Encoder[String].contramap(_.renderString)
  )

  sealed trait Item extends Product with Serializable

  object Item {
    final case class track(uri: String, durationMs: Int, name: String) extends Item derives Codec.AsObject

    implicit val codec: Codec[Item] =
      Codec.from(
        byTypeDecoder("track" -> Decoder[track]),
        Encoder.instance[Item] { case t: track =>
          asJsonWithType(t, "track")
        }
      )

  }

  final case class Player[_Ctx, _Item](context: _Ctx, item: _Item, progressMs: Int) {
    private def itemLens[NewItem]: PLens[Player[_Ctx, _Item], Player[_Ctx, NewItem], _Item, NewItem] =
      PLens[Player[_Ctx, _Item], Player[_Ctx, NewItem], _Item, NewItem](_.item)(i => _.copy(item = i))

    private def contextLens[NewContext]: PLens[Player[_Ctx, _Item], Player[NewContext, _Item], _Ctx, NewContext] =
      PLens[Player[_Ctx, _Item], Player[NewContext, _Item], _Ctx, NewContext](_.context)(c => _.copy(context = c))

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
    implicit def codec[_Ctx: Codec, _Item: Codec]: Codec[Player[_Ctx, _Item]] =
      Codec.forProduct3("context", "item", "progress_ms")(apply[_Ctx, _Item])(p => (p.context, p.item, p.progressMs))
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
    final case class playlist(href: Uri, uri: PlaylistUri) extends PlayerContext derives Codec.AsObject

    final case class album(href: Uri) extends PlayerContext derives Codec.AsObject

    final case class artist(href: Uri) extends PlayerContext derives Codec.AsObject

    implicit val codec: Codec[PlayerContext] = Codec.from(
      byTypeDecoder(
        "playlist" -> Decoder[playlist],
        "album" -> Decoder[album],
        "artist" -> Decoder[artist]
      ),
      Encoder.instance[PlayerContext] {
        case p: playlist => asJsonWithType(p, "playlist")
        case p: album    => asJsonWithType(p, "album")
        case p: artist   => asJsonWithType(p, "artist")
      }
    )

  }

  sealed trait Anything extends Product with Serializable
  case object Void extends Anything
  val anything: Anything = Void

  object Anything {
    implicit def entityCodec[F[_]: Concurrent]: EntityDecoder[F, Anything] = EntityDecoder.void[F].map(_ => anything)
  }

  final case class TokenResponse(accessToken: String, refreshToken: String) derives Codec.AsObject

  final case class RefreshedTokenResponse(accessToken: String) derives Codec.AsObject
}
