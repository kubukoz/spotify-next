package com.kubukoz.next

import cats.effect.Console.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext

sealed trait Choice extends Product with Serializable

object Choice {
  val nextTrack = Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack))

  val opts: Opts[Choice] = nextTrack
}

case object NextTrack extends Choice
case object DropTrack extends Choice
final case class FastForward(percentage: Int) extends Choice

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {
  val makeClient = BlazeClientBuilder[IO](ExecutionContext.global).resource

  val runApp: Choice => Spotify[IO] => IO[Unit] = {
    case NextTrack      => _.nextTrack
    case DropTrack      => _.dropTrack
    case FastForward(p) => _.fastForward(p)
  }

  val main: Opts[IO[ExitCode]] =
    Choice.opts.map(runApp).map(makeClient.map(implicit client => Spotify.instance).use).map(_.as(ExitCode.Success))
}
