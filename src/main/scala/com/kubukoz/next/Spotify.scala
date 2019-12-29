package com.kubukoz.next

import util.Config.Token
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.http4s.Status
import scala.util.control.NoStackTrace
import com.kubukoz.next.api.spotify.PlayerContext
import org.http4s.Uri
import org.http4s.Method.POST
import org.http4s.Method.DELETE
import org.http4s.Request
import io.circe.literal._
import com.kubukoz.next.api.spotify.Player

trait Spotify[F[_]] {
  def nextTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  trait Error extends NoStackTrace
  case object NotPlaying extends Error
  case object NoContext extends Error
  final case class InvalidContext[T](ctx: T) extends Error

  def instance[F[_]: Client: Console: Sync: Token.Ask]: Spotify[F] = new Spotify[F] {
    val client = implicitly[Client[F]]

    val console = Console[F]
    import console._

    private val player = client
      .expectOr[api.spotify.Player[Option[PlayerContext]]]("/v1/me/player") {
        case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
      }

    private val currentPlaylist: F[Player[PlayerContext.playlist]] = player
      .flatMap(_.sequenceContext.liftTo[F](NoContext))
      .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

    private def removeTrack(trackUri: String, playlistId: String): F[Unit] =
      client
        .expect[api.spotify.Anything](
          Request[F](DELETE, Uri.uri("/v1/playlists") / playlistId / "tracks")
            .withEntity(json"""{"tracks":[{"uri": $trackUri}]}""")
        )
        .void

    val nextTrack: F[Unit] =
      putStrLn("Switching to next track") *>
        client
          .expect[api.spotify.Anything](Request[F](POST, Uri.uri("/v1/me/player/next")))
          .void

    val dropTrack: F[Unit] =
      currentPlaylist.flatMap { player =>
        val trackUri = player.item.uri
        val playlistId = player.context.uri.playlist

        putStrLn("Removing track " + trackUri + " from playlist " + playlistId) *> removeTrack(trackUri, playlistId)
      } *> nextTrack

    def fastForward(percentage: Int): F[Unit] = ???
  }

}
