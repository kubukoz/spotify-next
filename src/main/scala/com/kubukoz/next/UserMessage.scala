package com.kubukoz.next

import cats.Show
import cats.effect.std
import cats.implicits.*
import cats.~>
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import com.kubukoz.next.api.sonos.SonosZones
import org.http4s.Uri
import java.nio.file.Path

enum UserMessage {
  case GoToUri(uri: Uri)
  case ConfigFileNotFound(path: Path, validInput: String)
  case SavedConfig(path: Path)
  case SavedToken
  case RefreshedToken
  // playback
  case SwitchingToNext
  case RemovingCurrentTrack(player: Player[PlayerContext.playlist, Item.track])
  case TooCloseToEnd
  case Seeking(desiredProgressPercent: Int)
  case CheckingSonos(url: Uri)
  case SonosNotFound
  case SonosFound(zones: SonosZones, roomName: String)
}

trait UserOutput[F[_]] {
  def print(msg: UserMessage): F[Unit]
  def mapK[G[_]](fk: F ~> G): UserOutput[G] = msg => fk(print(msg))
}

object UserOutput {
  def apply[F[_]](using F: UserOutput[F]): UserOutput[F] = F

  def toConsole[F[_]: std.Console]: UserOutput[F] = {
    given Show[Path] = Show.fromToString

    import UserMessage.*

    val stringify: UserMessage => String = {
      case GoToUri(uri)                         => show"Go to $uri"
      case ConfigFileNotFound(path, validInput) => show"Didn't find config file at $path. Should I create one? ($validInput/n)"
      case SavedConfig(path)                    => show"Saved config to new file at $path"
      case SavedToken                           => "Saved token to file"
      case RefreshedToken                       => "Refreshed token"
      case SwitchingToNext                      => "Switching to next track"
      case RemovingCurrentTrack(player)         =>
        show"""Removing track "${player.item.name}" (${player.item.uri.toFullUri}) from playlist ${player.context.uri.playlist}"""
      case TooCloseToEnd                        => "Too close to song's ending, rewinding to beginning"
      case Seeking(desiredProgressPercent)      => show"Seeking to $desiredProgressPercent%"
      case CheckingSonos(url)                   => show"Checking if Sonos API is available at $url..."
      case SonosNotFound                        => "Sonos not found, will access Spotify API directly"
      case SonosFound(zones, roomName)          => show"Found ${zones.zones.size} zone(s), will use room $roomName"
    }

    msg => std.Console[F].println(stringify(msg))
  }

}
