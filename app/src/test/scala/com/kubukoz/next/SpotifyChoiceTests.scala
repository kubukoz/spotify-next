package com.kubukoz.next

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.RefSource
import cats.implicits.*
import com.kubukoz.next.Spotify.DeviceInfo
import com.kubukoz.next.Spotify.SonosInfo

class SpotifyChoiceTests extends munit.CatsEffectSuite {

  import SpotifyChoice.Choice.*
  given UserOutput[IO] = _ => IO.unit

  val home = SonosInfo.Group("grid", "home")

  given SonosInfo[IO] with {
    def zones: IO[Option[NonEmptyList[SonosInfo.Group]]] = NonEmptyList.one(home).some.pure[IO]
  }

  def deviceInfo(
    available: RefSource[IO, Boolean]
  ) = new DeviceInfo[IO] {
    def isRestricted: IO[Boolean] = available.get.map(!_)
  }

  test("restricted") {
    given DeviceInfo[IO] with {
      def isRestricted: IO[Boolean] = IO.pure(true)
    }

    SpotifyChoice.choose[IO].flatMap(_.choose).map {
      assertEquals(_, Sonos(home))
    }
  }
  test("available") {
    given DeviceInfo[IO] with {
      def isRestricted: IO[Boolean] = IO.pure(false)
    }

    SpotifyChoice.choose[IO].flatMap(_.choose).map {
      assertEquals(_, Spotify)
    }
  }

  test("restricted -> available") {
    Ref[IO].of(false).flatMap { available =>
      given DeviceInfo[IO] = deviceInfo(available)

      SpotifyChoice
        .choose[IO]
        .flatMap { choice =>
          choice.choose *> available.set(true) *> choice.choose
        }
        .map {
          assertEquals(_, Spotify)
        }
    }
  }

  test("available -> restricted") {
    Ref[IO].of(true).flatMap { available =>
      given DeviceInfo[IO] = deviceInfo(available)

      SpotifyChoice
        .choose[IO]
        .flatMap { choice =>
          choice.choose *> available.set(false) *> choice.choose
        }
        .map {
          assertEquals(_, Sonos(home))
        }
    }
  }
}
