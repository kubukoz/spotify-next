package com.kubukoz.next

import util.Config.Token
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec._

trait Spotify[F[_]] {
  def nextTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  def instance[F[_]: Client: Console: Sync: Token.Ask]: Spotify[F] = new Spotify[F] {
    val client = implicitly[Client[F]]

    def putStrLn(a: String) = Console[F].putStrLn(a)

    private val player = client.expect[api.spotify.Player]("/v1/me/player")

    val nextTrack: F[Unit] = putStrLn("Switching to next track") *> player.map(_.toString).flatMap(putStrLn)

    def dropTrack: F[Unit] = ???
    def fastForward(percentage: Int): F[Unit] = ???
  }

}
