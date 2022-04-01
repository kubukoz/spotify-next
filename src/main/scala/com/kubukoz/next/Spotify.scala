package com.kubukoz.next

import cats.ApplicativeError
import cats.FlatMap
import cats.MonadError
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.Ref
import cats.implicits.*
import com.kubukoz.next.api.sonos
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import com.kubukoz.next.api.spotify.TrackUri
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

trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
  def jumpSection: F[Unit]
  def switch: F[Unit]
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

  def instance[F[_]: Playback: UserOutput: Concurrent: SpotifyApi: Switch](client: Client[F]): Spotify[F] =
    new Spotify[F] {

      private def requirePlaylist[A](player: Player[Option[PlayerContext], A]): F[Player[PlayerContext.playlist, A]] =
        player
          .unwrapContext
          .liftTo[F](NoContext)
          .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

      private def requireTrack[A](player: Player[A, Option[Item]]): F[Player[A, Item.track]] =
        player
          .unwrapItem
          .liftTo[F](NoItem)
          .flatMap(_.narrowItem[Item.track].liftTo[F])

      val skipTrack: F[Unit] =
        UserOutput[F].print(UserMessage.SwitchingToNext) *>
          Playback[F].nextTrack

      val dropTrack: F[Unit] =
        methods.player[F](client).flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
          val trackUri = player.item.uri
          val playlistId = player.context.uri.playlist

          UserOutput[F].print(UserMessage.RemovingCurrentTrack(player)) *>
            skipTrack *>
            SpotifyApi[F].removeTrack(playlistId, List(Track(trackUri.toFullUri)))
        }

      def fastForward(percentage: Int): F[Unit] =
        methods
          .player[F](client)
          .flatMap(requireTrack)
          .fproduct { player =>
            val currentLength = player.progress_ms
            val totalLength = player.item.duration_ms
            ((currentLength * 100 / totalLength) + percentage)
          }
          .flatMap {
            case (_, desiredProgressPercent) if desiredProgressPercent >= 100 =>
              UserOutput[F].print(UserMessage.TooCloseToEnd) *>
                Playback[F].seek(0)

            case (player, desiredProgressPercent) =>
              val desiredProgressMs = desiredProgressPercent * player.item.duration_ms / 100
              UserOutput[F].print(UserMessage.Seeking(desiredProgressPercent)) *>
                Playback[F].seek(desiredProgressMs)
          }

      def jumpSection: F[Unit] = methods
        .player[F](client)
        .flatMap(requireTrack)
        .flatMap { player =>
          val track = player.item

          val currentLength = player.progress_ms.millis

          SpotifyApi[F]
            .getAudioAnalysis(track.uri.id)
            .flatMap { analysis =>
              analysis
                .sections
                .zipWithIndex
                .find { case (section, _) => section.startSeconds.seconds > currentLength }
                .traverse { case (section, index) =>
                  val percentage = (section.startSeconds.seconds * 100 / track.duration_ms.millis).toInt

                  UserOutput[F].print(
                    UserMessage.Jumping(
                      sectionNumber = index + 1,
                      sectionsTotal = analysis.sections.length,
                      percentTotal = percentage
                    )
                  ) *>
                    Playback[F].seek(section.startSeconds.seconds.toMillis.toInt)
                }
                .pipe(OptionT(_))
                .getOrElseF(UserOutput[F].print(UserMessage.TooCloseToEnd) *> Playback[F].seek(0))
            }
        }
        .void

      val switch: F[Unit] = Switch[F].switch

    }

  trait Playback[F[_]] {
    def nextTrack: F[Unit]
    def seek(ms: Int): F[Unit]
  }

  object Playback {
    def apply[F[_]](using F: Playback[F]): Playback[F] = F

    def spotifyInstance[F[_]: SpotifyApi]: Playback[F] = new:
      val nextTrack: F[Unit] = SpotifyApi[F].nextTrack()
      def seek(ms: Int): F[Unit] = SpotifyApi[F].seek(ms)

    def sonosInstance[F[_]: SonosApi](group: SonosInfo.Group): Playback[F] = new:

      val nextTrack: F[Unit] =
        SonosApi[F].nextTrack(GroupId(group.id))

      def seek(ms: Int): F[Unit] =
        SonosApi[F].seek(GroupId(group.id), SeekInputBody(Milliseconds(ms)))

    def suspend[F[_]: FlatMap](choose: F[Playback[F]]): Playback[F] = new:
      def nextTrack: F[Unit] = choose.flatMap(_.nextTrack)
      def seek(ms: Int): F[Unit] = choose.flatMap(_.seek(ms))

  }

  trait Switch[F[_]] {
    def switch: F[Unit]
  }

  object Switch {
    def apply[F[_]](using F: Switch[F]): Switch[F] = F

    def spotifyInstance[F[_]: SpotifyApi: FlatMap]: Switch[F] = new:

      val switch: F[Unit] = SpotifyApi[F]
        .getAvailableDevices()
        .map(_.devices.take(1).map(_.id))
        .flatMap(SpotifyApi[F].transferPlayback)

    def sonosInstance[F[_]: SonosApi](group: SonosInfo.Group): Switch[F] = new:
      val switch: F[Unit] =
        SonosApi[F].play(GroupId(group.id))

    def suspend[F[_]: FlatMap](choose: F[Switch[F]]): Switch[F] = new:
      val switch: F[Unit] = choose.flatMap(_.switch)
  }

  trait DeviceInfo[F[_]] {
    def isRestricted: F[Boolean]
  }

  object DeviceInfo {
    def apply[F[_]](using F: DeviceInfo[F]): DeviceInfo[F] = F

    def instance[F[_]: Concurrent](client: Client[F]): DeviceInfo[F] = new DeviceInfo[F] {
      val isRestricted: F[Boolean] = methods.player[F](client).map(_.device.is_restricted)
    }

  }

  trait SonosInfo[F[_]] {
    def zones: F[List[SonosInfo.Group]]
  }

  object SonosInfo {
    def apply[F[_]](using F: SonosInfo[F]): SonosInfo[F] = F

    case class Group(id: String, name: String)

    def instance[F[_]: UserOutput: SonosApi](using MonadError[F, ?]): SonosInfo[F] =
      new SonosInfo[F] {

        def zones: F[List[SonosInfo.Group]] = UserOutput[F].print(UserMessage.CheckingSonos) *>
          SonosApi[F].getHouseholds().flatMap {
            _.households.flatTraverse { household =>
              SonosApi[F].getGroups(household.id).map {
                _.groups.map(group => Group(group.id.value, group.name))
              }
            }
          }

      }

  }

  private object methods {

    def player[F[_]: Concurrent](client: Client[F]): F[Player[Option[PlayerContext], Option[Item]]] =
      client.expectOr(com.kubukoz.next.api.spotify.baseUri / "v1" / "me" / "player") {
        case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
        case response                                         => InvalidStatus(response.status).pure[F].widen
      }

  }

}
