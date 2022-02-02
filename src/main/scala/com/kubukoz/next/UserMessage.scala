package com.kubukoz.next

import cats.Show
import cats.effect.std
import cats.implicits.*
import cats.~>
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import org.http4s.Uri
import fs2.io.file.Path
import cats.data.NonEmptyList
import com.kubukoz.next.Spotify.SonosInfo

enum UserMessage {
  case GoToUri(uri: Uri)
  case ConfigFileNotFound(path: Path, validInput: String)
  case SavedConfig(path: Path)
  case SavedToken
  case RefreshedToken(kind: String)

  // playback
  case SwitchingToNext
  case RemovingCurrentTrack(player: Player[PlayerContext.playlist, Item.track])
  case TooCloseToEnd
  case Jumping(sectionNumber: Int, sectionsTotal: Int, percentTotal: Int)
  case Seeking(desiredProgressPercent: Int)

  // sonos
  case CheckingSonos
  case SonosNotFound
  case SonosFound(groups: NonEmptyList[SonosInfo.Group], group: SonosInfo.Group)
  case DeviceRestricted
  case DirectControl
}

trait UserOutput[F[_]] {
  def print(msg: UserMessage): F[Unit]
  def mapK[G[_]](fk: F ~> G): UserOutput[G] = msg => fk(print(msg))
}

object UserOutput {
  def apply[F[_]](using F: UserOutput[F]): UserOutput[F] = F

  def toConsole[F[_]: std.Console](sonosBaseUrl: Uri): UserOutput[F] = {
    given Show[Path] = Show.fromToString

    import UserMessage.*

    val stringify: UserMessage => String = {
      case GoToUri(uri)                         => show"Go to $uri"
      case ConfigFileNotFound(path, validInput) => show"Didn't find config file at $path. Should I create one? ($validInput/n)"
      case SavedConfig(path)                    => show"Saved config to new file at $path"
      case SavedToken                           => "Saved token to file"
      case RefreshedToken(kind: String)         => s"Refreshed $kind token"
      case SwitchingToNext                      => "Switching to next track"
      case RemovingCurrentTrack(player)         =>
        show"""Removing track "${player.item.name}" (${player.item.uri.toFullUri}) from playlist ${player.context.uri.playlist}"""
      case TooCloseToEnd                        => "Too close to song's ending, rewinding to beginning"
      case Seeking(desiredProgressPercent)      => show"Seeking to $desiredProgressPercent%"
      case Jumping(sectionNumber, sectionsTotal, percentTotal) =>
        show"Jumping to section $sectionNumber/$sectionsTotal ($percentTotal%)"
      case CheckingSonos                                       => show"Checking if Sonos API is available at $sonosBaseUrl..."
      case SonosNotFound                                       => "Sonos not found, using fallback"
      case SonosFound(groups, group) => show"Found ${groups.size} zone(s), will use group ${group.name} (${group.id})"
      case DeviceRestricted          => "Device restricted, trying to switch to Sonos API control..."
      case DirectControl             => "Switching to direct Spotify API control..."
    }

    msg => std.Console[F].println(stringify(msg))
  }

}
