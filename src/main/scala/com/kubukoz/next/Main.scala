package com.kubukoz.next

import com.kubukoz.next.util.Config
import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import cats.mtl.ApplicativeAsk
import java.lang.System
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger

sealed trait Choice extends Product with Serializable

object Choice {
  val nextTrack = Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack))

  val opts: Opts[Choice] = nextTrack
}

case object NextTrack extends Choice
case object DropTrack extends Choice
final case class FastForward(percentage: Int) extends Choice

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {

  val makeClient =
    (
      BlazeClientBuilder[IO](ExecutionContext.global)
        .resource
        .map(FollowRedirect(maxRedirects = 5))
        .map(RequestLogger[IO](logHeaders = true, logBody = true))
        .map(ResponseLogger[IO](logHeaders = true, logBody = true)),
      Blocker[IO]
    ).tupled.evalMap {
      case (client, blocker) =>
        implicit val http4sClient = client

        fs2
          .io
          .file
          .readAll[IO](Paths.get(System.getProperty("user.home") + "/.spotify-next.json"), blocker, 4096)
          .through(io.circe.fs2.byteStreamParser[IO])
          .through(io.circe.fs2.decoder[IO, Config])
          .compile
          .lastOrError
          .map { config =>
            implicit val tokenAsk: Config.Token.Ask[IO] = ApplicativeAsk.const(config.token)

            Spotify.instance[IO]
          }
    }

  val runApp: Choice => Spotify[IO] => IO[Unit] = {
    case NextTrack      => _.nextTrack
    case DropTrack      => _.dropTrack
    case FastForward(p) => _.fastForward(p)
  }

  val main: Opts[IO[ExitCode]] =
    Choice.opts.map(runApp).map(makeClient.use).map(_.as(ExitCode.Success))
}
