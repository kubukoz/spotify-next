package com.kubukoz.next

trait Runner[F[_]] {
  def run(choice: Choice): F[Unit]
}

object Runner {
  def apply[F[_]](implicit F: Runner[F]): Runner[F] = F

  def instance[F[_]: Spotify: LoginProcess]: Runner[F] = {
    case Choice.Login          => LoginProcess[F].login
    case Choice.SkipTrack      => Spotify[F].skipTrack
    case Choice.DropTrack      => Spotify[F].dropTrack
    case Choice.FastForward(p) => Spotify[F].fastForward(p)
    case Choice.JumpSection    => Spotify[F].jumpSection
  }

}
