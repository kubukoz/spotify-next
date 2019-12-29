package com.kubukoz.next

import com.kubukoz.next.util.middlewares
import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import java.lang.System
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger

import cats.data.NonEmptyList
import org.http4s.client.Client
import com.kubukoz.next.util.Config
import com.olegpy.meow.hierarchy.deriveApplicativeAsk
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
        Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack))
      )
      .reduceK
}

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {

  val configPath = Paths.get(System.getProperty("user.home") + "/.spotify-next.json")

  def makeLoader[F[_]: Sync: ContextShift] =
    Blocker[F].map(ConfigLoader.default[F](configPath, _)).evalMap(ConfigLoader.cached(_))

  def loginUser[F[_]: Console: ConcurrentEffect: Timer: ConfigLoader]: F[Unit] = {
    implicit val login: Login[F] = Login.blaze[F]

    for {
      token <- Login[F].server
      config <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = token)
      _ <- ConfigLoader[F].saveConfig(newConfig)
      _ <- Console[F].putStrLn("Saved token to file")
    } yield ()
  }

  def makeClient[F[_]: ConcurrentEffect: ContextShift: Timer: Console: ConfigLoader]: Resource[F, Client[F]] =
    BlazeClientBuilder(ExecutionContext.global)
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true))
      .map(ResponseLogger(logHeaders = true, logBody = true))
      .map(middlewares.withToken)
      .map(middlewares.retryUnauthorizedWith(loginUser[F]))
      .map(middlewares.implicitHost("api.spotify.com"))

  def makeSpotify[F[_]: Console: Sync: Config.Ask](client: Client[F]) = {
    implicit val theClient = client

    Spotify.instance[F]
  }

  import Choice._

  def runApp(implicit loader: ConfigLoader[IO]): Choice => Spotify[IO] => IO[Unit] = {
    case Login          => _ => loginUser[IO]
    case NextTrack      => _.nextTrack
    case DropTrack      => _.dropTrack
    case FastForward(p) => _.fastForward(p)
  }

  val main: Opts[IO[ExitCode]] =
    Choice
      .opts
      .map { choice =>
        makeLoader[IO].use { implicit loader =>
          makeClient[IO].map(makeSpotify[IO](_)).use(runApp(loader)(choice))
        }
      }
      .map(_.as(ExitCode.Success))
}
