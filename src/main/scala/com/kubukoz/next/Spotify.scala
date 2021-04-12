package com.kubukoz.next

import java.time.Duration

import cats.data.Kleisli
import cats.effect.Concurrent
import cats.implicits._
import com.kubukoz.next.api.sonos
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import io.circe.literal._
import org.http4s.Method.DELETE
import org.http4s.Method.POST
import org.http4s.Method.PUT
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import cats.FlatMap
import cats.effect.kernel.Ref
import cats.data.OptionT
import cats.effect.kernel.RefSink

trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
}

object Spotify {

  def apply[F[_]](implicit F: Spotify[F]): Spotify[F] = F

  sealed trait Error extends Throwable
  case object NotPlaying extends Error
  case class InvalidStatus(status: Status) extends Error
  case object NoContext extends Error
  case object NoItem extends Error
  final case class InvalidContext[T](ctx: T) extends Error
  final case class InvalidItem[T](ctx: T) extends Error

  def instance[F[_]: Playback: Client: UserOutput: Concurrent]: Spotify[F] =
    new Spotify[F] {
      val client = implicitly[Client[F]]

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
        methods.player[F].run(client).flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
          val trackUri = player.item.uri
          val playlistId = player.context.uri.playlist

          UserOutput[F].print(UserMessage.RemovingCurrentTrack(player)) *>
            skipTrack *>
            methods.removeTrack[F](trackUri, playlistId).run(client)
        }

      def fastForward(percentage: Int): F[Unit] =
        methods
          .player[F]
          .run(client)
          .flatMap(requireTrack)
          .fproduct { player =>
            val currentLength = player.progressMs
            val totalLength = player.item.durationMs
            ((currentLength * 100 / totalLength) + percentage)
          }
          .flatMap {
            case (_, desiredProgressPercent) if desiredProgressPercent >= 100 =>
              UserOutput[F].print(UserMessage.TooCloseToEnd) *>
                Playback[F].seek(0)

            case (player, desiredProgressPercent) =>
              val desiredProgressMs = desiredProgressPercent * player.item.durationMs / 100
              UserOutput[F].print(UserMessage.Seeking(desiredProgressPercent)) *>
                Playback[F].seek(desiredProgressMs)
          }

    }

  trait Playback[F[_]] {
    def nextTrack: F[Unit]
    def seek(ms: Int): F[Unit]
  }

  object Playback {
    def apply[F[_]](implicit F: Playback[F]): Playback[F] = F

    trait MakeForSpotify[F[_]] {
      def make: Playback[F]
    }

    object MakeForSpotify {
      def apply[F[_]](implicit F: MakeForSpotify[F]): MakeForSpotify[F] = F

      def instance[F[_]: Concurrent](client: Client[F]): MakeForSpotify[F] = new MakeForSpotify[F] {

        def make: Playback[F] = new Playback[F] {
          val nextTrack: F[Unit] =
            client.expect[api.spotify.Anything](Request[F](POST, methods.SpotifyApi / "v1" / "me" / "player" / "next")).void

          def seek(ms: Int): F[Unit] = {
            val uri = (methods.SpotifyApi / "v1" / "me" / "player" / "seek").withQueryParam("position_ms", ms)

            client.expect[api.spotify.Anything](Request[F](PUT, uri)).void
          }

        }

      }

    }

    // Produces a sonos instance of Playback
    trait MakeForSonos[F[_]] {
      def make(room: String): Playback[F]
    }

    object MakeForSonos {
      def apply[F[_]](implicit F: MakeForSonos[F]): MakeForSonos[F] = F

      def instance[F[_]: Concurrent](sonosBaseUrl: Uri, client: Client[F]): MakeForSonos[F] = new MakeForSonos[F] {

        def make(room: String): Playback[F] =
          new Playback[F] {
            val nextTrack: F[Unit] =
              client.expect[api.spotify.Anything](sonosBaseUrl / room / "next").void

            def seek(ms: Int): F[Unit] = {
              val seconds = Duration.ofMillis(ms.toLong).toSeconds().toString

              client.expect[api.spotify.Anything](sonosBaseUrl / room / "timeseek" / seconds).void
            }

          }

      }

    }

    def suspend[F[_]: FlatMap](choose: F[Playback[F]]): Playback[F] = new Playback[F] {
      def nextTrack: F[Unit] = choose.flatMap(_.nextTrack)
      def seek(ms: Int): F[Unit] = choose.flatMap(_.seek(ms))
    }

    def build[F[_]: Concurrent: UserOutput: DeviceInfo: SonosInfo: MakeForSonos: MakeForSpotify]: F[Playback[F]] = {
      def spotifyInstanceF(lastUsedRoom: RefSink[F, None.type]) = lastUsedRoom.set(None).as(MakeForSpotify[F].make)

      def sonosInstanceF(lastUsedRoom: Ref[F, Option[String]]): F[Option[Playback[F]]] = {

        val fetchZones: F[Option[sonos.SonosZones]] =
          UserOutput[F].print(UserMessage.CheckingSonos) *>
            SonosInfo[F].zones

        def extractRoom(zones: sonos.SonosZones): F[String] = {
          val roomName = zones.zones.head.coordinator.roomName

          UserOutput[F].print(UserMessage.SonosFound(zones, roomName)) *>
            lastUsedRoom.set(roomName.some).as(roomName)
        }

        val roomF: F[Option[String]] =
          OptionT(lastUsedRoom.get)
            .orElse(
              OptionT(fetchZones).semiflatMap(extractRoom)
            )
            .value

        roomF
          .flatMap {
            case None =>
              UserOutput[F].print(UserMessage.SonosNotFound).as(none)

            case Some(roomName) =>
              MakeForSonos[F].make(roomName).some.pure[F]
          }
      }

      def showChange(nowRestricted: Boolean): F[Unit] =
        UserOutput[F].print {
          if (nowRestricted) UserMessage.DeviceRestricted
          else UserMessage.DirectControl
        }

      Ref[F].of(false).flatMap { isRestrictedRef =>
        Ref[F].of(Option.empty[String]).map { lastSonosRoom =>
          val resetRoom = (lastSonosRoom: RefSink[F, Option[String]]).narrow[None.type]

          suspend[F] {
            DeviceInfo[F]
              .isRestricted
              .flatTap { newValue =>
                isRestrictedRef.getAndSet(newValue).flatMap { oldValue =>
                  showChange(newValue).unlessA(oldValue === newValue)
                }
              }
              .ifM(
                ifTrue = sonosInstanceF(lastSonosRoom).flatMap {
                  case None                => spotifyInstanceF(resetRoom)
                  case Some(sonosInstance) => sonosInstance.pure[F]
                },
                ifFalse = spotifyInstanceF(resetRoom)
              )
          }
        }
      }
    }

  }

  trait DeviceInfo[F[_]] {
    def isRestricted: F[Boolean]
  }

  object DeviceInfo {
    def apply[F[_]](implicit F: DeviceInfo[F]): DeviceInfo[F] = F

    def instance[F[_]: Concurrent](client: Client[F]): DeviceInfo[F] = new DeviceInfo[F] {
      val isRestricted: F[Boolean] = methods.player[F].run(client).map(_.device.isRestricted)
    }

  }

  trait SonosInfo[F[_]] {
    def zones: F[Option[sonos.SonosZones]]
  }

  object SonosInfo {
    def apply[F[_]](implicit F: SonosInfo[F]): SonosInfo[F] = F

    def instance[F[_]: Concurrent](sonosBaseUrl: Uri, client: Client[F]): SonosInfo[F] = new SonosInfo[F] {

      def zones: F[Option[sonos.SonosZones]] =
        client
          .get(sonosBaseUrl / "zones") {
            case response if response.status.isSuccess => response.as[sonos.SonosZones].map(_.some)
            case _                                     => none[sonos.SonosZones].pure[F]
          }
          .handleError(_ => None)

    }

  }

  private object methods {
    import org.http4s.syntax.all._

    val SpotifyApi = uri"https://api.spotify.com"

    type Method[F[_], A] = Kleisli[F, Client[F], A]

    def player[F[_]: Concurrent]: Method[F, Player[Option[PlayerContext], Option[Item]]] =
      Kleisli {
        _.expectOr(SpotifyApi / "v1" / "me" / "player") {
          case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
          case response                                         => InvalidStatus(response.status).pure[F].widen
        }
      }

    def removeTrack[F[_]: Concurrent](trackUri: String, playlistId: String): Method[F, Unit] =
      Kleisli {
        _.expect[api.spotify.Anything](
          Request[F](DELETE, SpotifyApi / "v1" / "playlists" / playlistId / "tracks")
            .withEntity(json"""{"tracks":[{"uri": $trackUri}]}""")
        ).void
      }

  }

}
