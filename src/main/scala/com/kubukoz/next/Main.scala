package com.kubukoz.next

import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.implicits._
import com.kubukoz.next.util.Config
import com.monovore.decline._
import com.monovore.decline.effect._

import java.io.EOFException

enum Choice {
  case Login
  case SkipTrack
  case DropTrack
  case FastForward(percentage: Int)
}

object Choice {
  val ffOpts = Opts.argument[Int]("step").map(FastForward(_)).withDefault(FastForward(10))

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

object Main extends CommandIOApp(name = "spotify-next", header = "spotify-next: Gather great music.", version = BuildInfo.version) {

  import Program._

  def makeProgram[F[_]: Async: Console]: Resource[F, Runner[F]] = {
    implicit val userOutput: UserOutput[F] = UserOutput.toConsole

    Resource
      .eval(makeLoader[F])
      .flatMap { implicit loader =>
        implicit val configAsk: Config.Ask[F] = loader.configAsk

        makeBasicClient[F].map { rawClient =>
          implicit val login: Login[F] = Login.blaze[F](rawClient)
          implicit val loginProcess: LoginProcess[F] = LoginProcess.instance

          implicit val spotify: Spotify[F] = makeSpotify(apiClient[F].apply(rawClient))

          Runner.instance[F]
        }
      }
  }

  val mainOpts: Opts[IO[Unit]] = Choice
    .opts
    .map { choice =>
      makeProgram[IO].use(_.run(choice))
    }

  val runRepl: IO[Unit] = {
    // EOF thrown on Ctrl+D
    val prompt = IO.print("next> ") *> IO.readLine.map(_.some).recover { case _: EOFException => none[String] }

    val input = fs2
      .Stream
      .repeatEval(prompt)
      .map(_.map(_.trim))
      .filter(_.forall(_.nonEmpty))
      .unNoneTerminate
      .map(_.split("\\s+").toList)
      .onComplete(fs2.Stream.exec(IO.println("Bye!")))

    def reportError(e: Throwable): IO[Unit] =
      Console[IO].errorln("Command failed with exception: ") *> IO(e.printStackTrace())

    fs2.Stream.exec(IO.println("Loading REPL...")) ++
      fs2
        .Stream
        .resource(makeProgram[IO])
        .evalTap(_ => IO.println(s"Welcome to the spotify-next ${BuildInfo.version} REPL! Type in a command to begin"))
        .map(runner => Command("", "")(Choice.opts).map(runner.run))
        .flatMap { command =>
          input
            .map(command.parse(_, sys.env).leftMap(_.toString))
            .evalMap(_.fold(IO.println(_), _.handleErrorWith(reportError)))
        }
  }.compile.drain

  val repl: Opts[Unit] = Opts
    .subcommand("repl", "Run application in interactive mode")(Opts.unit)

  val main: Opts[IO[ExitCode]] =
    (mainOpts <+> repl.as(runRepl)).map(_.as(ExitCode.Success))
}
