package com.kubukoz.next

import cats.ApplicativeError
import cats.FlatMap
import cats.MonadError
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.Ref
import cats.implicits.*
import com.kubukoz.next.api.sonos
import com.kubukoz.next.client.spotify.Item
import com.kubukoz.next.client.spotify.Player
import com.kubukoz.next.client.spotify.PlayerContext
import com.kubukoz.next.client.spotify.TrackUri
import com.kubukoz.next.sonos.GroupId
import com.kubukoz.next.sonos.Milliseconds
import com.kubukoz.next.sonos.SeekInputBody
import com.kubukoz.next.sonos.SonosApi
import com.kubukoz.next.spotify.AudioAnalysis
import com.kubukoz.next.spotify.SpotifyApi
import com.kubukoz.next.spotify.Track
import io.circe.syntax.*
import org.http4s.Method.DELETE
import org.http4s.Method.POST
import org.http4s.Method.PUT
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client

import scala.concurrent.duration.*
import scala.util.chaining.*
import cats.data.NonEmptyList
import com.kubukoz.next.client.spotify.PlaylistUri

trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
  def jumpSection: F[Unit]
  def switch: F[Unit]
  def move: F[Unit]
}

object Spotify {

  def apply[F[_]](using F: Spotify[F]): Spotify[F] = F

  enum Error extends Throwable {
    case NotPlaying
    case InvalidStatus(status: Status)
    case NoContext
    case NoItem
    case InvalidContext[T](ctx: T)
    case InvalidItem[T](item: T)
  }

  import Error.*

  def instance[F[_]: Playback: UserOutput: Concurrent: SpotifyApi: Switch: Analysis: ConfigLoader]: Spotify[F] =
    new Spotify[F] {
      private val getPlayer = SpotifyApi[F].getPlayer().map(Player.fromApiPlayer)

      private val showNowPlaying = getPlayer.flatMap { player =>
        player.item.traverse_ { case track: Item.Track =>
          UserOutput[F].print(UserMessage.NowPlaying(track))
        }
      }

      private def requirePlaylist[A](player: Player[Option[PlayerContext], A]): F[Player[PlayerContext.Playlist, A]] =
        player
          .unwrapContext
          .liftTo[F](NoContext)
          .flatMap(_.narrowContext[PlayerContext.Playlist].liftTo[F])

      private def requireTrack[A](player: Player[A, Option[Item]]): F[Player[A, Item.Track]] =
        player
          .unwrapItem
          .liftTo[F](NoItem)
          .flatMap(_.narrowItem[Item.Track].liftTo[F])

      val skipTrack: F[Unit] =
        UserOutput[F].print(UserMessage.SwitchingToNext)
          *> Playback[F].nextTrack
          <* showNowPlaying

      val dropTrack: F[Unit] =
        getPlayer.flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
          val trackUri = player.item.uri
          val playlistId = player.context.uri.playlist

          UserOutput[F].print(UserMessage.RemovingCurrentTrack(player)) *>
            skipTrack *>
            SpotifyApi[F].removeTrack(playlistId, List(Track(trackUri.toFullUri)))
        }

      def fastForward(percentage: Int): F[Unit] =
        getPlayer
          .flatMap(requireTrack)
          .fproduct { player =>
            val currentLength = player.progress
            val totalLength = player.item.duration
            ((currentLength * 100 / totalLength) + percentage).toInt
          }
          .flatMap {
            case (_, desiredProgressPercent) if desiredProgressPercent >= 100 =>
              UserOutput[F].print(UserMessage.TooCloseToEnd) *>
                Playback[F].seek(0.millis)

            case (player, desiredProgressPercent) =>
              val desiredProgress = desiredProgressPercent * player.item.duration / 100
              UserOutput[F].print(UserMessage.Seeking(desiredProgressPercent)) *>
                Playback[F].seek(desiredProgress)
          }

      def jumpSection: F[Unit] = getPlayer
        .flatMap(requireTrack)
        .flatMap { player =>
          val track = player.item

          val currentLength = player.progress

          Analysis[F]
            .getAnalysis(track.uri)
            .flatMap { analysis =>
              analysis
                .sections
                .zipWithIndex
                .find { case (section, _) => section.startSeconds.seconds > (currentLength + 1.second) }
                .traverse { case (section, index) =>
                  val percentage = (section.startSeconds.seconds * 100 / track.duration).toInt

                  UserOutput[F].print(
                    UserMessage.Jumping(
                      sectionNumber = index + 1,
                      sectionsTotal = analysis.sections.length,
                      percentTotal = percentage
                    )
                  ) *>
                    Playback[F].seek(section.startSeconds.seconds)
                }
                .pipe(OptionT(_))
                .getOrElseF(UserOutput[F].print(UserMessage.TooCloseToEnd) *> Playback[F].seek(0.millis))
            }
        }
        .void

      val switch: F[Unit] = Switch[F].switch

      val move: F[Unit] = getPlayer.flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
        val trackUri = player.item.uri
        val playlistId = player.context.uri.playlist

        ConfigLoader[F].loadConfig.map(_.targetPlaylist).flatMap { targetPlaylist =>
          UserOutput[F].print(UserMessage.MovingCurrentTrack(player, targetPlaylist)) *>
            SpotifyApi[F].addItemsToPlaylist(targetPlaylist.playlist, List(trackUri.toFullUri)) *>
            skipTrack *>
            SpotifyApi[F].removeTrack(playlistId, List(Track(trackUri.toFullUri)))
        }
      }

    }

  trait Playback[F[_]] {
    def nextTrack: F[Unit]
    def seek(progress: FiniteDuration): F[Unit]
  }

  object Playback {
    def apply[F[_]](using F: Playback[F]): Playback[F] = F

    def spotifyInstance[F[_]: SpotifyApi]: Playback[F] = new:
      val nextTrack: F[Unit] = SpotifyApi[F].nextTrack()
      def seek(progress: FiniteDuration): F[Unit] = SpotifyApi[F].seek(progress.toMillis.toInt)

    def sonosInstance[F[_]: SonosApi](group: SonosInfo.Group): Playback[F] = new:

      val nextTrack: F[Unit] =
        SonosApi[F].nextTrack(GroupId(group.id))

      def seek(progress: FiniteDuration): F[Unit] =
        SonosApi[F].seek(GroupId(group.id), SeekInputBody(Milliseconds(progress.toMillis.toInt)))

    def suspend[F[_]: FlatMap](choose: F[Playback[F]]): Playback[F] = new:
      def nextTrack: F[Unit] = choose.flatMap(_.nextTrack)
      def seek(progress: FiniteDuration): F[Unit] = choose.flatMap(_.seek(progress))

    def makeFromChoice[F[_]: SpotifyApi: SonosApi: FlatMap](
      choice: SpotifyChoice[F]
    ): Playback[F] =
      Spotify
        .Playback
        .suspend {
          choice
            .choose
            .map(
              _.fold(
                sonos = room => Spotify.Playback.sonosInstance[F](room),
                spotify = Spotify.Playback.spotifyInstance[F]
              )
            )
        }

  }

  trait Switch[F[_]] {
    def switch: F[Unit]
  }

  object Switch {
    def apply[F[_]](using F: Switch[F]): Switch[F] = F

    def spotifyInstance[F[_]: SpotifyApi: FlatMap: UserOutput]: Switch[F] = new:

      val switch: F[Unit] =
        SpotifyApi[F]
          .getAvailableDevices()
          .map(_.devices.toNel)
          .flatMap {
            case None =>
              UserOutput[F].print(UserMessage.NoDevices)

            case Some(devices) =>
              val device = devices.find(_.id.isDefined).getOrElse(sys.error("no valid device found"))
              UserOutput[F].print(UserMessage.SwitchingPlayback(PlaybackTarget.Spotify(device))) *>
                SpotifyApi[F].transferPlayback(List(device.id.getOrElse(sys.error("impossible"))))
          }

    def sonosInstance[F[_]: SonosApi: SonosInfo: UserOutput: FlatMap]: Switch[F] = new:

      val switch: F[Unit] =
        SonosInfo[F].zones.flatMap {
          case None => UserOutput[F].print(UserMessage.SonosNotFound)

          case Some(groups) =>
            val group = groups.head

            UserOutput[F].print(UserMessage.SwitchingPlayback(PlaybackTarget.Sonos(group))) *>
              SonosApi[F].play(GroupId(group.id))
        }

    def suspend[F[_]: FlatMap](choose: F[Switch[F]]): Switch[F] = new:
      val switch: F[Unit] = choose.flatMap(_.switch)
  }

  trait DeviceInfo[F[_]] {
    def isRestricted: F[Boolean]
  }

  object DeviceInfo {
    def apply[F[_]](using F: DeviceInfo[F]): DeviceInfo[F] = F

    def instance[F[_]: Concurrent: SpotifyApi]: DeviceInfo[F] = new DeviceInfo[F] {
      val isRestricted: F[Boolean] = SpotifyApi[F].getPlayer().map(_.device.isRestricted)
    }

  }

  trait SonosInfo[F[_]] {
    def zones: F[Option[NonEmptyList[SonosInfo.Group]]]
  }

  object SonosInfo {
    def apply[F[_]](using F: SonosInfo[F]): SonosInfo[F] = F

    case class Group(id: String, name: String)

    def instance[F[_]: UserOutput: SonosApi](using MonadError[F, ?], fs2.Compiler[F, F]): SonosInfo[F] =
      new SonosInfo[F] {

        val zones: F[Option[NonEmptyList[SonosInfo.Group]]] = UserOutput[F].print(UserMessage.CheckingSonos) *>
          fs2
            .Stream
            .evals(SonosApi[F].getHouseholds().map(_.households))
            .map(_.id)
            .flatMap(SonosApi[F].getGroups(_).map(_.groups).pipe(fs2.Stream.evals(_)))
            .map(group => Group(group.id.value, group.name))
            .compile
            .toList
            .map(_.toNel)

      }

  }

}
