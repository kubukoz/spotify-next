package com.kubukoz.next

import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import cats.data.NonEmptyList
import ConfigLoader.deriveAskFromLoader

sealed trait Choice extends Product with Serializable

object Choice {
  case object Login extends Choice
  case object NextTrack extends Choice
  case object DropTrack extends Choice
  final case class FastForward(percentage: Int) extends Choice

  val opts: Opts[Choice] =
    NonEmptyList
      .of[Opts[Choice]](
        Opts.subcommand("login", "Log into Spotify")(Opts(Login)),
        Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack)),
        Opts.subcommand("drop", "Drop current track from the current playlist and skip to the next track")(
          Opts(DropTrack)
        )
      )
      .reduceK
}

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {

  import Program._

  def runApp[F[_]: ConfigLoader: Login: Console: Monad]: Choice => Spotify[F] => F[Unit] = {
    case Choice.Login          => _ => loginUser[F]
    case Choice.NextTrack      => _.nextTrack
    case Choice.DropTrack      => _.dropTrack
    case Choice.FastForward(p) => _.fastForward(p)
  }

  val main: Opts[IO[ExitCode]] =
    Choice
      .opts
      .map { choice =>
        makeLoader[IO].use { implicit loader =>
          implicit val login: Login[IO] = Login.blaze[IO]

          makeClient[IO].map(makeSpotify[IO](_)).use(runApp[IO].apply(choice))
        }
      }
      .map(_.as(ExitCode.Success))
}
