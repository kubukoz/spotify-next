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

  val ffOpts = Opts.argument[Int]("step").map(FastForward).withDefault(FastForward(10))

  val opts: Opts[Choice] =
    NonEmptyList
      .of[Opts[Choice]](
        Opts.subcommand("login", "Log into Spotify")(Opts(Login)),
        Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack)),
        Opts.subcommand("drop", "Drop current track from the current playlist and skip to the next track")(
          Opts(DropTrack)
        ),
        Opts.subcommand("forward", "Fast forward the current track by a percentage of its length (10% by default)")(
          ffOpts
        ),
        Opts.subcommand("n", "Alias for `next`")(Opts(NextTrack)),
        Opts.subcommand("d", "Alias for `drop`")(Opts(DropTrack)),
        Opts.subcommand("f", "Alias for `forward`")(ffOpts)
      )
      .reduceK
}

object Main extends CommandIOApp(name = "spotify-next", header = "spotify-next: Gather great music.") {

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

  val runRepl: IO[Unit] = {
    val input = fs2
      .Stream
      .repeatEval(putStr("next> ") *> readLn.map(Option(_)))
      .map(_.map(_.trim))
      .filter(_.forall(_.nonEmpty))
      .unNoneTerminate
      .map(_.split("\\s+").toList)
      .onComplete(fs2.Stream.eval_(putStrLn("Bye!")))

    def reportError(e: Throwable): IO[Unit] =
      putError("Command failed with exception: ") *> IO(e.printStackTrace())

    fs2.Stream.eval_(putStrLn("Loading REPL...")) ++
      fs2
        .Stream
        .resource(makeProgram)
        .evalTap(_ => putStrLn("Welcome to the spotify-next REPL! Type in a command to begin"))
        .map(Command("", "")(Choice.opts).map(_))
        .flatMap { command =>
          input
            .map(command.parse(_, sys.env).leftMap(_.toString))
            .evalMap(_.fold(putStrLn(_), _.handleErrorWith(reportError)))
        }
  }.compile.drain

  val repl: Opts[Unit] = Opts
    .subcommand("repl", "Run application in interactive mode")(Opts.unit)

  val main: Opts[IO[ExitCode]] =
    (mainOpts <+> repl.as(runRepl)).map(_.as(ExitCode.Success))
}
