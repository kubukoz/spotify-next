package com.kubukoz.next

import org.http4s.client.Client

trait Spotify[F[_]] {
  def nextTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  def instance[F[_]: Client: Console]: Spotify[F] = new Spotify[F] {
    val nextTrack: F[Unit] = Console[F].putStrLn("Switching to next track")
    def dropTrack: F[Unit] = ???
    def fastForward(percentage: Int): F[Unit] = ???
  }

}
