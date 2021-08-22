package com.kubukoz.next

import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.Ref
import cats.implicits.*
import com.kubukoz.next.Spotify.DeviceInfo
import com.kubukoz.next.Spotify.SonosInfo
import com.kubukoz.next.api.sonos
import com.kubukoz.next.api.spotify.AudioAnalysis
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import com.kubukoz.next.api.spotify.TrackUri
import io.circe.syntax.*
import scala.concurrent.duration.*

object SpotifyChoice {

  enum Choice {
    case Sonos(room: String)
    case Spotify

    def fold[A](sonos: String => A, spotify: => A): A = this match {
      case Sonos(room) => sonos(room)
      case Spotify     => spotify
    }

  }

  /** Stateful instantiation of an effect that chooses values. Think F[ReadOnlyRef[F, A]] - the outer effect allocates state, the inner
    * effect (F[F[...]]) calculates the current value.
    *
    * The result returned can differ between calls to the inner F, but will share state with all calls of the inner F inside the same outer
    * F.
    */
  def choose[F[_]: Concurrent: UserOutput: DeviceInfo: SonosInfo]: F[F[Choice]] =
    Ref[F].of(false).flatMap { isRestrictedRef =>
      Ref[F].of(Option.empty[String]).map { lastSonosRoom =>
        val spotifyInstanceF = lastSonosRoom.set(None).as(Choice.Spotify)

        val sonosInstanceF: F[Option[Choice]] = {
          val fetchZones: F[Option[sonos.SonosZones]] =
            UserOutput[F].print(UserMessage.CheckingSonos) *>
              SonosInfo[F].zones

          def extractRoom(zones: sonos.SonosZones): F[String] = {
            val roomName = zones.zones.head.coordinator.roomName

            UserOutput[F].print(UserMessage.SonosFound(zones, roomName)) *>
              lastSonosRoom.set(roomName.some).as(roomName)
          }

          val roomF: F[Option[String]] =
            OptionT(lastSonosRoom.get)
              .orElse(
                OptionT(fetchZones).semiflatMap(extractRoom)
              )
              .value

          roomF
            .flatMap {
              case None =>
                UserOutput[F].print(UserMessage.SonosNotFound).as(none)

              case Some(roomName) =>
                Choice.Sonos(roomName).some.pure[F]
            }
        }

        def showChange(nowRestricted: Boolean): F[Unit] =
          UserOutput[F].print {
            if (nowRestricted) UserMessage.DeviceRestricted
            else UserMessage.DirectControl
          }

        DeviceInfo[F]
          .isRestricted
          .flatTap { newValue =>
            isRestrictedRef.getAndSet(newValue).flatMap { oldValue =>
              showChange(newValue).unlessA(oldValue === newValue)
            }
          }
          .ifM(
            ifTrue = OptionT(sonosInstanceF).getOrElseF(spotifyInstanceF),
            ifFalse = spotifyInstanceF
          )
      }
    }

}
