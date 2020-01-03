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

    def narrowContext[DesiredContext <: _Ctx: ClassTag]: Either[InvalidContext[_Ctx], Player[DesiredContext, _Item]] =
      Player.traverseByContext.traverse(this) {
        case desired: DesiredContext => desired.asRight
        case other                   => InvalidContext(other).asLeft
      }

    def narrowItem[DesiredItem <: _Item: ClassTag]: Either[InvalidItem[_Item], Player[_Ctx, DesiredItem]] =
      Player.traverseByItem.traverse(this) {
        case desired: DesiredItem => desired.asRight
        case other                => InvalidItem(other).asLeft
      }
  }

  object Player {

    import cats.tagless.syntax.invariantK._
    import com.kubukoz.next.util.traverse._

    // Just some Traverse derivations based on nested tuples, nothing to see here...
    // As long as you follow the types you'll be fine
    def traverseByContext[Item_]: NonEmptyTraverse[Player[*, Item_]] =
      NonEmptyTraverse[((Int, Item_), *)]
        .imapK(
          λ[((Int, Item_), *) ~> Player[*, Item_]] { case ((progress, item), ctx) => Player(ctx, item, progress) }
        )(
          λ[Player[*, Item_] ~> ((Int, Item_), *)](p => ((p.progressMs, p.item), p.context))
        )

    def traverseByItem[Ctx_]: NonEmptyTraverse[Player[Ctx_, *]] =
      NonEmptyTraverse[((Int, Ctx_), *)]
        .imapK(λ[((Int, Ctx_), *) ~> Player[Ctx_, *]] {
          case ((progress, ctx), item) =>
            Player(ctx, item, progress)
        })(
          λ[Player[Ctx_, *] ~> ((Int, Ctx_), *)](p => ((p.progressMs, p.context), p.item))
        )

    implicit def codec[_Ctx: Codec, _Item: Codec]: Codec[Player[_Ctx, _Item]] = deriveConfiguredCodec
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

  final case class TokenResponse(accessToken: String, refreshToken: String)

  object TokenResponse {
    implicit val codec: Codec[TokenResponse] = deriveConfiguredCodec
  }
}
