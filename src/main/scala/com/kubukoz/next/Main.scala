package com.kubukoz.next

import com.kubukoz.next.util.Config
import cats.effect.Console.implicits._
import cats.effect.Console.io._
import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import java.lang.System
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.HttpRoutes
import org.http4s.headers.`Content-Type`
import org.http4s.dsl.Http4sDsl
import com.kubukoz.next.util.Config.Token
import org.http4s.Uri
import scala.concurrent.duration._
import org.http4s.MediaType
import org.http4s.Charset
import java.lang.ClassLoader

sealed trait Choice extends Product with Serializable

object Choice {
  val nextTrack: Opts[Choice] = Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack))

  val opts: Opts[Choice] =
    nextTrack
}

case object NextTrack extends Choice
case object DropTrack extends Choice
final case class FastForward(percentage: Int) extends Choice

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {

  val configPath = Paths.get(System.getProperty("user.home") + "/.spotify-next.json")

  def makeLoader[F[_]: Sync: ContextShift] =
    Blocker[F].map(ConfigLoader.default[F](configPath, _)).evalMap(ConfigLoader.cached(_))

  def loginUser[F[_]: Console: ConcurrentEffect: Timer: ContextShift] =
    makeLoader[F].use { implicit loader =>
      implicit val configAsk = loader.loadAsk
      implicit val login: Login[F] = Login.blaze[F]

      for {
        token <- Login[F].server
        config <- ConfigLoader[F].loadConfig
        newConfig = config.copy(token = token)
        _ <- ConfigLoader[F].saveConfig(newConfig)
        _ <- Console[F].putStrLn("Saved token to file")
      } yield ()
    }

  def makeClient[F[_]: ConcurrentEffect: ContextShift: Console]: Resource[F, Spotify[F]] =
    BlazeClientBuilder[F](ExecutionContext.global)
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger[F](logHeaders = true, logBody = true))
      .map(ResponseLogger[F](logHeaders = true, logBody = true))
      .flatMap { implicit client =>
        makeLoader[F].map(_.loadAsk).map { implicit configAsk =>
          import com.olegpy.meow.hierarchy.deriveApplicativeAsk
          Spotify.instance[F]
        }
      }

  val runApp: Choice => Spotify[IO] => IO[Unit] = {
    case NextTrack      => _.nextTrack
    case DropTrack      => _.dropTrack
    case FastForward(p) => _.fastForward(p)
  }

  val loginCommand =
    Opts.subcommand("login", "Request login URL")(Opts(loginUser))

  val main: Opts[IO[ExitCode]] =
    (loginCommand <+> Choice.opts.map(runApp).map(makeClient[IO].use)).map(_.as(ExitCode.Success))
}
