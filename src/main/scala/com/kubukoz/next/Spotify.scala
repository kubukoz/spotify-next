package com.kubukoz.next

import cats.data.Kleisli
import cats.effect.Concurrent
import cats.effect.std.Console
import cats.implicits._
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import io.circe.literal._
import org.http4s.Method.DELETE
import org.http4s.Method.POST
import org.http4s.Method.PUT
import org.http4s.Request
import org.http4s.Status
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

import util.Config.Token

trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  def apply[F[_]](implicit F: Spotify[F]): Spotify[F] = F

  sealed trait Error extends Throwable
  case object NotPlaying extends Error
  case class InvalidStatus(status: Status) extends Error
  case object NoContext extends Error
  case object NoItem extends Error
  final case class InvalidContext[T](ctx: T) extends Error
  final case class InvalidItem[T](ctx: T) extends Error

  def instance[F[_]: Client: Console: Concurrent: Token.Ask]: Spotify[F] =
    new Spotify[F] {
      val client = implicitly[Client[F]]

      val console = Console[F]
      import console._

      private def requirePlaylist[A](player: Player[Option[PlayerContext], A]): F[Player[PlayerContext.playlist, A]] =
        player
          .unwrapContext
          .liftTo[F](NoContext)
          .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

      private def requireTrack[A](player: Player[A, Option[Item]]): F[Player[A, Item.track]] =
        player
          .unwrapItem
          .liftTo[F](NoItem)
          .flatMap(_.narrowItem[Item.track].liftTo[F])

      val skipTrack: F[Unit] =
        println("Switching to next track") *>
          methods.nextTrack[F].run(client)

      val dropTrack: F[Unit] =
        methods.player[F].run(client).flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
          val trackUri = player.item.uri
          val playlistId = player.context.uri.playlist

          println(show"Removing track $trackUri from playlist $playlistId") *>
            methods.removeTrack[F](trackUri, playlistId).run(client)
        } *> skipTrack

      def fastForward(percentage: Int): F[Unit] =
        methods
          .player[F]
          .run(client)
          .flatMap(requireTrack)
          .fproduct { player =>
            val currentLength = player.progressMs
            val totalLength = player.item.durationMs
            ((currentLength * 100 / totalLength) + percentage)
          }
          .flatMap {
            case (_, desiredProgressPercent) if desiredProgressPercent >= 100 =>
              println("Too close to song's ending, rewinding to beginning") *> methods.seek[F](0).run(client)

            case (player, desiredProgressPercent) =>
              val desiredProgressMs = desiredProgressPercent * player.item.durationMs / 100
              println(show"Seeking to $desiredProgressPercent%") *> methods.seek[F](desiredProgressMs).run(client)
          }

    }

  object methods {
    import org.http4s.syntax.all._
    private val SpotifyApi = uri"https://api.spotify.com"

    type Method[F[_], A] = Kleisli[F, Client[F], A]

    def player[F[_]: Concurrent]: Method[F, Player[Option[PlayerContext], Option[Item]]] =
      Kleisli {
        _.expectOr(SpotifyApi / "v1" / "me" / "player") {
          case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
          case response                                         => InvalidStatus(response.status).pure[F].widen
        }
      }

    def nextTrack[F[_]: Concurrent]: Method[F, Unit] =
      Kleisli {
        _.expect[api.spotify.Anything](Request[F](POST, SpotifyApi / "v1" / "me" / "player" / "next")).void
      }

    def removeTrack[F[_]: Concurrent](trackUri: String, playlistId: String): Method[F, Unit] =
      Kleisli {
        _.expect[api.spotify.Anything](
          Request[F](DELETE, SpotifyApi / "v1" / "playlists" / playlistId / "tracks")
            .withEntity(json"""{"tracks":[{"uri": $trackUri}]}""")
        ).void
      }

    def seek[F[_]: Concurrent](positionMs: Int): Method[F, Unit] =
      Kleisli {
        _.expect[api.spotify.Anything](
          Request[F](PUT, (SpotifyApi / "v1" / "me" / "player" / "seek").withQueryParam("position_ms", positionMs))
        ).void
      }

  }

}
