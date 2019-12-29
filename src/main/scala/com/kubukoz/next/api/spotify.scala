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

  implicit def optionCodec[A: Codec]: Codec[Option[A]] = Codec.from(Decoder.decodeOption[A], Encoder.encodeOption[A])

  implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder[String].emap(s => Uri.fromString(s).leftMap(failure => failure.details)),
    Encoder[String].contramap(_.renderString)
  )

  final case class Item(uri: String)

  object Item {
    implicit val codec: Codec[Item] = deriveConfiguredCodec
  }

  final case class Player[Ctx](context: Ctx, item: Item) {

    def narrowContext[DesiredContext <: Ctx: ClassTag]: Either[InvalidContext[Ctx], Player[DesiredContext]] =
      Player.traverseByContext.traverse(this) {
        case desired: DesiredContext => desired.asRight
        case other                   => InvalidContext(other).asLeft
      }

    def sequenceContext[F[_], A](implicit isF: Player[Ctx] <:< Player[F[A]], apply: Apply[F]): F[Player[A]] =
      Player.traverseByContext.nonEmptySequence[F, A](isF(this))
  }

  object Player {

    val traverseByContext: NonEmptyTraverse[Player] = new NonEmptyTraverse[Player] {
      def foldLeft[A, B](fa: Player[A], b: B)(f: (B, A) => B): B = f(b, fa.context)
      def foldRight[A, B](fa: Player[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = f(fa.context, lb)
      def reduceLeftTo[A, B](fa: Player[A])(f: A => B)(g: (B, A) => B): B = f(fa.context)
      def reduceRightTo[A, B](fa: Player[A])(f: A => B)(g: (A, Eval[B]) => Eval[B]): Eval[B] = Eval.later(f(fa.context))

      def nonEmptyTraverse[G[_]: Apply, A, B](fa: Player[A])(f: A => G[B]): G[Player[B]] =
        f(fa.context).map(ctx => fa.copy(context = ctx))
    }

    implicit def codec[Ctx: Codec]: Codec[Player[Ctx]] = deriveConfiguredCodec
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
