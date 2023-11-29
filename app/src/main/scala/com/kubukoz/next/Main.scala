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
import LoginProcess.given
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import cats.effect.unsafe.IORuntime
import fs2.io.file.Files
import fs2.io.net.Network
import org.http4s.client.Client
import monocle.Lens
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token

enum Choice {
  case Login
  case SkipTrack
  case DropTrack

  case FastForward(
    percentage: Int
  )

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

  def makeLogin[F[_]: Config.Ask: ConfigLoader: UserOutput: Network: Async](
    name: String,
    rawClient: Client[F],
    oauthKernel: OAuth.Kernel[F]
  )(
    tokensLens: Lens[
      Config,
      (
        Option[Token],
        Option[RefreshToken]
      )
    ]
  ) = {
    val login = Login.ember[F](OAuth.fromKernel[F](rawClient, oauthKernel))

    LoginProcess
      .instance[F](login, tokensLens)
      .orRefresh(RefreshTokenProcess.instance(name, login, tokensLens))
  }

  def makeProgram[F[_]: Async: Network: Files: Console: Logger]: Resource[F, Runner[F]] = {
    given UserOutput[F] = UserOutput.toConsole(sonos.baseUri)

    for {
      given ConfigLoader[F] <- makeLoader[F].toResource
      rawClient             <- makeBasicClient[F]
      given Config.Ask[F] = ConfigLoader[F].configAsk
      spotifyLoginProcess = makeLogin("Spotify", rawClient, OAuth.spotify)(Config.spotifyTokensLens)
      sonosLoginProcess = makeLogin("Sonos", rawClient, OAuth.sonos)(Config.sonosTokensLens)
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
    } yield Runner.instance[F](spotifyLoginProcess |+| sonosLoginProcess)

  }

  val mainOpts: Opts[IO[Unit]] = Choice
    .opts
    .map { choice =>
      makeProgram[IO].use(_.run(choice))
    }

  val runRepl: IO[Unit] = {
    // EOF thrown on Ctrl+D
    val prompt = IO.print("next> ") *>
      fs2
        .io
        .stdinUtf8[IO](4096)
        .through(fs2.text.lines[IO])
        .head
        .compile
        .last

    val input = fs2
      .Stream
      .repeatEval(prompt)
      .map(_.map(_.trim))
      .filter(_.forall(_.nonEmpty))
      .unNoneTerminate
      .map(_.split("\\s+").toList)
      .onComplete(fs2.Stream.exec(IO.println("Bye!")))

    def reportError(
      e: Throwable
    ): IO[Unit] =
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
