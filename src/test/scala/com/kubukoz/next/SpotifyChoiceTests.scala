package com.kubukoz.next

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.RefSource
import cats.implicits.*
import com.kubukoz.next.Spotify.DeviceInfo
import com.kubukoz.next.Spotify.SonosInfo
import com.kubukoz.next.sonos.GetZonesOutput
import com.kubukoz.next.sonos.Zone
import com.kubukoz.next.sonos.ZoneCoordinator

class SpotifyChoiceTests extends munit.CatsEffectSuite {

  import SpotifyChoice.Choice.*
  given UserOutput[IO] = _ => IO.unit

  given SonosInfo[IO] with {
    def zones: IO[Option[GetZonesOutput]] = Some(GetZonesOutput(List(Zone(ZoneCoordinator("home"))))).pure[IO]
  }

  def deviceInfo(available: RefSource[IO, Boolean]) = new DeviceInfo[IO] {
    def isRestricted: IO[Boolean] = available.get.map(!_)
  }

  test("restricted") {
    given DeviceInfo[IO] with {
      def isRestricted: IO[Boolean] = IO.pure(true)
    }

    SpotifyChoice.choose[IO].flatten.map {
      assertEquals(_, Sonos("home"))
    }
  }
  test("available") {
    given DeviceInfo[IO] with {
      def isRestricted: IO[Boolean] = IO.pure(false)
    }

    SpotifyChoice.choose[IO].flatten.map {
      assertEquals(_, Spotify)
    }
  }

  test("restricted -> available") {
    Ref[IO].of(false).flatMap { available =>
      given DeviceInfo[IO] = deviceInfo(available)

      SpotifyChoice
        .choose[IO]
        .flatMap { choose =>
          choose *> available.set(true) *> choose
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
        .flatMap { choose =>
          choose *> available.set(false) *> choose
        }
        .map {
          assertEquals(_, Sonos("home"))
        }
    }
  }
}
