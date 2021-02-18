package com.kubukoz.next

import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import cats.data.NonEmptyList
import com.kubukoz.next.util.Config

sealed trait Choice extends Product with Serializable

object Choice {
  case object Login extends Choice
  case object SkipTrack extends Choice
  case object DropTrack extends Choice
  final case class FastForward(percentage: Int) extends Choice

  val ffOpts = Opts.argument[Int]("step").map(FastForward).withDefault(FastForward(10))

  val opts: Opts[Choice] =
    NonEmptyList
      .of[Opts[Choice]](
        Opts.subcommand("login", "Log into Spotify")(Opts(Login)),
        Opts.subcommand("skip", "Skip to next track without any changes")(Opts(SkipTrack)),
        Opts.subcommand("drop", "Drop current track from the current playlist and skip to the next track")(
          Opts(DropTrack)
        ),
        Opts.subcommand("forward", "Fast forward the current track by a percentage of its length (10% by default)")(
          ffOpts
        ),
        Opts.subcommand("s", "Alias for `skip`")(Opts(SkipTrack)),
        Opts.subcommand("d", "Alias for `drop`")(Opts(DropTrack)),
        Opts.subcommand("f", "Alias for `forward`")(ffOpts)
      )
      .reduceK

}

object Main extends CommandIOApp(name = "spotify-next", header = "spotify-next: Gather great music.") {

  import Program._

  def runApp[F[_]: Spotify: ConfigLoader: Login: Console: Monad]: Choice => F[Unit] = {
    case Choice.Login          => loginUser[F]
    case Choice.SkipTrack      => Spotify[F].skipTrack
    case Choice.DropTrack      => Spotify[F].dropTrack
    case Choice.FastForward(p) => Spotify[F].fastForward(p)
  }

  import Console.io._

  val makeProgram: Resource[IO, Choice => IO[Unit]] =
    makeLoader[IO]
      .flatMap { implicit loader =>
        implicit val configAsk: Config.Ask[IO] = loader.configAsk

        makeBasicClient[IO].map { rawClient =>
          import scala.concurrent.ExecutionContext.Implicits.global

          implicit val login: Login[IO] = Login.blaze[IO](rawClient)
          implicit val spotify: Spotify[IO] = makeSpotify(apiClient[IO].apply(rawClient))

          runApp[IO]
        }
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
