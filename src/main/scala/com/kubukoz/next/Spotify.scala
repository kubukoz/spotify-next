package com.kubukoz.next

import util.Config.Token
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.http4s.Status
import com.kubukoz.next.api.spotify.PlayerContext
import org.http4s.Uri
import org.http4s.Method.POST
import org.http4s.Method.PUT
import org.http4s.Method.DELETE
import org.http4s.Request
import io.circe.literal._
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.Item
import cats.data.Kleisli
import cats.tagless.finalAlg

@finalAlg
trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  sealed trait Error extends Throwable
  case object NotPlaying extends Error
  case object NoContext extends Error
  case object NoItem extends Error
  final case class InvalidContext[T](ctx: T) extends Error
  final case class InvalidItem[T](ctx: T) extends Error

  def instance[F[_]: Client: Console: Sync: Token.Ask]: Spotify[F] = new Spotify[F] {
    val client = implicitly[Client[F]]

    val console = Console[F]
    import console._

    private def requirePlaylist[A](player: Player[Option[PlayerContext], A]): F[Player[PlayerContext.playlist, A]] =
      player
        .byContext
        .sequence
        .map(_.value)
        .liftTo[F](NoContext)
        .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

    private def requireTrack[A](player: Player[A, Option[Item]]): F[Player[A, Item.track]] =
      player
        .sequence
        .liftTo[F](NoItem)
        .flatMap(_.narrowItem[Item.track].liftTo[F])

    val skipTrack: F[Unit] =
      putStrLn("Switching to next track") *>
        methods.nextTrack[F].run(client)

    val dropTrack: F[Unit] =
      methods.player[F].run(client).flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
        val trackUri = player.item.uri
        val playlistId = player.context.uri.playlist

        putStrLn(show"Removing track $trackUri from playlist $playlistId") *>
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
            putStrLn("Too close to song's ending, rewinding to beginning") *> methods.seek[F](0).run(client)

          case (player, desiredProgressPercent) =>
            val desiredProgressMs = desiredProgressPercent * player.item.durationMs / 100
            putStrLn(show"Seeking to $desiredProgressPercent%") *> methods.seek[F](desiredProgressMs).run(client)
        }
  }

  object methods {
    type Method[F[_], A] = Kleisli[F, Client[F], A]

    def player[F[_]: Sync]: Method[F, Player[Option[PlayerContext], Option[Item]]] =
      Kleisli {
        _.expectOr("/v1/me/player") {
          case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
        }
      }

    def nextTrack[F[_]: Sync]: Method[F, Unit] = Kleisli {
      _.expect[api.spotify.Anything](Request[F](POST, Uri.uri("/v1/me/player/next"))).void
    }

    def removeTrack[F[_]: Sync](trackUri: String, playlistId: String): Method[F, Unit] =
      Kleisli {
        _.expect[api.spotify.Anything](
          Request[F](DELETE, Uri.uri("/v1/playlists") / playlistId / "tracks")
            .withEntity(json"""{"tracks":[{"uri": $trackUri}]}""")
        ).void
      }

    def seek[F[_]: Sync](positionMs: Int): Method[F, Unit] = Kleisli {
      _.expect[api.spotify.Anything](
        Request[F](PUT, Uri.uri("/v1/me/player/seek").withQueryParam("position_ms", positionMs))
      ).void
    }
  }
}
