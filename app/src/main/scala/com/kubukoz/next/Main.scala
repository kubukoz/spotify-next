package com.kubukoz.next

import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.implicits.*
import com.kubukoz.next.util.Config
import com.kubukoz.next.api.sonos
import com.monovore.decline.*
import com.monovore.decline.effect.*
import cats.effect.implicits.*
import java.io.EOFException
import LoginProcess.given
import org.typelevel.log4cats.Logger
// import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.noop.NoOpLogger
import cats.effect.unsafe.IORuntime

enum Choice {
  case Login
  case SkipTrack
  case DropTrack
  case FastForward(percentage: Int)
  case JumpSection
  case Switch
  case Move
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
        Opts.subcommand("switch", "Switch device (Spotify/Sonos)")(Opts(Switch)),
        Opts.subcommand("move", "Move song to playlist A")(Opts(Move)),
        Opts.subcommand("jump", "Fast forward the current track to the next section")(Opts(JumpSection)),
        Opts.subcommand("s", "Alias for `skip`")(Opts(SkipTrack)),
        Opts.subcommand("d", "Alias for `drop`")(Opts(DropTrack)),
        Opts.subcommand("f", "Alias for `forward`")(ffOpts),
        Opts.subcommand("j", "Alias for `jump`")(Opts(JumpSection)),
        Opts.subcommand("w", "Alias for `switch`")(Opts(Switch)),
        Opts.subcommand("m", "Alias for `move`")(Opts(Move))
      )
      .reduceK

}

object Main extends CommandIOApp(name = "spotify-next", header = "spotify-next: Gather great music.", version = BuildInfo.version) {

  override protected def runtime: IORuntime = RuntimePlatform.default

  import Program.*
  given Logger[IO] = NoOpLogger[IO]
  // given Logger[IO] = Slf4jLogger.getLogger[IO]

  def makeProgram[F[_]: Async: Console: Logger]: Resource[F, Runner[F]] = {
    given UserOutput[F] = UserOutput.toConsole(sonos.baseUri)

    for {
      given ConfigLoader[F] <- makeLoader[F].toResource
      rawClient             <- makeBasicClient[F]
      given Config.Ask[F] = ConfigLoader[F].configAsk
      // obviously quite a lot of duplication here...
      spotifyLogin = Login.ember[F](OAuth.fromKernel[F](rawClient, OAuth.spotify))
      spotifyLoginProcess = LoginProcess
                              .instance[F](
                                spotifyLogin,
                                Config.spotifyTokensLens
                              )
                              .orRefresh(RefreshTokenProcess.instance("Spotify", spotifyLogin, Config.spotifyTokensLens))
      sonosLogin = Login.ember[F](OAuth.fromKernel[F](rawClient, OAuth.sonos))
      sonosLoginProcess = LoginProcess
                            .instance[F](
                              sonosLogin,
                              Config.sonosTokensLens
                            )
                            .orRefresh(RefreshTokenProcess.instance("Sonos", sonosLogin, Config.sonosTokensLens))
      given Spotify[F]      <-
        makeSpotify[F](
          apiClient(
            spotifyLoginProcess,
            _.token
          )
            .apply(rawClient),
          apiClient(
            sonosLoginProcess,
            _.sonosToken
          ).andThen(sonosMiddlewares).apply(rawClient)
        ).toResource
    } yield Runner.instance[F](
      LoginProcess.combineAll(spotifyLoginProcess :: sonosLoginProcess :: Nil)
    )

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
