package com.kubukoz.next

import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.Ref
import cats.implicits.*
import com.kubukoz.next.Spotify.DeviceInfo
import com.kubukoz.next.Spotify.SonosInfo
import cats.data.NonEmptyList

trait SpotifyChoice[F[_]] {
  def choose: F[SpotifyChoice.Choice]
}

object SpotifyChoice {

  def apply[F[_]](
    implicit F: SpotifyChoice[F]
  ): SpotifyChoice[F] = F

  enum Choice {

    case Sonos(
      room: SonosInfo.Group
    )

    case Spotify

    def fold[A](
      sonos: SonosInfo.Group => A,
      spotify: => A
    ): A = this match {
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
  def choose[F[_]: Concurrent: UserOutput: DeviceInfo: SonosInfo]: F[SpotifyChoice[F]] =
    Ref[F].of(false).flatMap { isRestrictedRef =>
      Ref[F].of(Option.empty[SonosInfo.Group]).map { lastSonosRoom =>
        val spotifyInstanceF = lastSonosRoom.set(None).as(Choice.Spotify)

        val sonosInstanceF: F[Option[Choice]] = {
          def extractRoom(
            groups: NonEmptyList[SonosInfo.Group]
          ): F[SonosInfo.Group] = {
            val group = groups.head

            UserOutput[F].print(UserMessage.SonosFound(groups, group)) *>
              lastSonosRoom.set(group.some).as(group)
          }

          val roomF: F[Option[SonosInfo.Group]] =
            OptionT(lastSonosRoom.get)
              .orElse(
                OptionT(SonosInfo[F].zones).semiflatMap(extractRoom)
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

        def showChange(
          nowRestricted: Boolean
        ): F[Unit] =
          UserOutput[F].print {
            if (nowRestricted) UserMessage.DeviceRestricted
            else UserMessage.DirectControl
          }

        val doChoose = DeviceInfo[F]
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

        new {
          val choose: F[Choice] = doChoose
        }
      }
    }

}
