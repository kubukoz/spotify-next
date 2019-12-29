package com.kubukoz.next

import util.Config.Token
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.Credentials
import org.http4s.AuthScheme
import org.http4s.circe.CirceEntityCodec._
import org.http4s.Uri
import org.http4s.Uri.RegName
import org.http4s.util.CaseInsensitiveString

trait Spotify[F[_]] {
  def nextTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  def instance[F[_]: Client: Console: Sync: Token.Ask]: Spotify[F] = new Spotify[F] {
    def putStrLn(a: String) = Console[F].putStrLn(a)

    val client =
      Client[F] { req =>
        Resource.liftF(Token.ask[F]).flatMap { token =>
          val newReq =
            req
              .withUri(
                req.uri.copy(authority = Some(Uri.Authority(None, RegName(CaseInsensitiveString("api.spotify.com")))))
              )
              .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token.value)))

          implicitly[Client[F]].run(newReq)
        }
      }

    private val player = client.expect[api.spotify.Player]("/v1/me/player")

    val nextTrack: F[Unit] = putStrLn("Switching to next track") *> player.map(_.toString).flatMap(putStrLn)

    def dropTrack: F[Unit] = ???
    def fastForward(percentage: Int): F[Unit] = ???
  }

}
