package com.kubukoz.next

import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import cats.data.NonEmptyList
import ConfigLoader.deriveAskFromLoader
import com.kubukoz.next.util.Config

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

  def runApp[F[_]: ConfigLoader: Login: Console: Monad]: Spotify[F] => Choice => F[Unit] = spotify => {
    case Choice.Login          => loginUser[F]
    case Choice.NextTrack      => spotify.nextTrack
    case Choice.DropTrack      => spotify.dropTrack
    case Choice.FastForward(p) => spotify.fastForward(p)
  }

  implicit def login[F[_]: ConcurrentEffect: Timer: Console: Config.Ask]: Login[F] = Login.blaze[F]

  import Console.io._

  val makeProgram = makeLoader[IO]
    .flatMap { implicit loader =>
      makeClient[IO].map(makeSpotify[IO](_)).map(runApp[IO])
    }

  val mainOpts: Opts[IO[Unit]] = Choice
    .opts
    .map { choice =>
      makeProgram.use(_.apply(choice))
    }

  val runRepl: IO[Nothing] = makeProgram.use { program =>
    val replCommand = Command("REPL", "")(Choice.opts).map(program)

    putStrLn("Welcome to the spotify-next REPL! Type in a command to begin") *>
      (putStr("next> ") *> readLn)
        .map(_.split("\\s+").toSeq)
        .flatMap(replCommand.parse(_, sys.env).leftMap(_.toString).fold(putStrLn(_), identity))
        .foreverM
  }

  val repl: Opts[Unit] = Opts
    .subcommand("repl", "Run application in interactive mode")(Opts.unit)

  val main: Opts[IO[ExitCode]] =
    mainOpts.map(_.as(ExitCode.Success)) <+> repl.as(runRepl)
}
