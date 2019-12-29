package com.kubukoz.next

import util.Config.Token
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._
import org.http4s.Status
import scala.util.control.NoStackTrace
import com.kubukoz.next.api.spotify.PlayerContext
import org.http4s.Uri
import org.http4s.Method.POST
import org.http4s.Request

trait Spotify[F[_]] {
  def nextTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  trait Error extends NoStackTrace
  case object NotPlaying extends Error
  final case class InvalidContext(ctx: PlayerContext) extends Error

  def instance[F[_]: Client: Console: Sync: Token.Ask]: Spotify[F] = new Spotify[F] {
    val client = implicitly[Client[F]]

    val console = Console[F]
    import console._

    val player = client
      .expectOr[api.spotify.Player[PlayerContext]]("/v1/me/player") {
        case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
      }

    private val currentPlaylist = player
      .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

    val nextTrack: F[Unit] =
      putStrLn("Switching to next track") *>
        client
          .expect[api.spotify.Anything](Request[F](POST, Uri.uri("/v1/me/player/next")))
          .void

    def dropTrack: F[Unit] = currentPlaylist.flatMap(s => putStrLn(s.toString()))
    def fastForward(percentage: Int): F[Unit] = ???
  }

}
