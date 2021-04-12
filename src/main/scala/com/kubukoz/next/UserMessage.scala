package com.kubukoz.next

import cats.Show
import cats.effect.std
import cats.implicits._
import cats.~>
import com.kubukoz.next.api.sonos.SonosZones
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import org.http4s.Uri

import java.nio.file.Path

sealed trait UserMessage extends Product with Serializable

object UserMessage {
  final case class GoToUri(uri: Uri) extends UserMessage
  final case class ConfigFileNotFound(path: Path, validInput: String) extends UserMessage
  final case class SavedConfig(path: Path) extends UserMessage
  case object SavedToken extends UserMessage
  case object RefreshedToken extends UserMessage

  // playback
  case object SwitchingToNext extends UserMessage
  final case class RemovingCurrentTrack(player: Player[PlayerContext.playlist, Item.track]) extends UserMessage
  case object TooCloseToEnd extends UserMessage
  final case class Seeking(desiredProgressPercent: Int) extends UserMessage

  // setup
  case object DeviceRestricted extends UserMessage
  case object DirectControl extends UserMessage
  case object CheckingSonos extends UserMessage
  case object SonosNotFound extends UserMessage
  final case class SonosFound(zones: SonosZones, roomName: String) extends UserMessage
}

trait UserOutput[F[_]] {
  def print(msg: UserMessage): F[Unit]
  def mapK[G[_]](fk: F ~> G): UserOutput[G] = msg => fk(print(msg))
}

object UserOutput {
  def apply[F[_]](implicit F: UserOutput[F]): UserOutput[F] = F

  def toConsole[F[_]: std.Console](sonosBaseUrl: Uri): UserOutput[F] = {
    implicit val showPath: Show[Path] = Show.fromToString

    import UserMessage._

    val stringify: UserMessage => String = {
      case GoToUri(uri)                         => show"Go to $uri"
      case ConfigFileNotFound(path, validInput) => show"Didn't find config file at $path. Should I create one? ($validInput/n)"
      case SavedConfig(path)                    => show"Saved config to new file at $path"
      case SavedToken                           => "Saved token to file"
      case RefreshedToken                       => "Refreshed token"
      case SwitchingToNext                      => "Switching to next track"
      case RemovingCurrentTrack(player)         =>
        show"""Removing track "${player.item.name}" (${player.item.uri}) from playlist ${player.context.uri.playlist}"""
      case TooCloseToEnd                        => "Too close to song's ending, rewinding to beginning"
      case Seeking(desiredProgressPercent)      => show"Seeking to $desiredProgressPercent%"
      case CheckingSonos                        => show"Checking if Sonos API is available at $sonosBaseUrl..."
      case SonosNotFound                        => "Sonos not found, using fallback"
      case SonosFound(zones, roomName)          => show"Found ${zones.zones.size} zone(s), will use room $roomName"
      case DeviceRestricted                     => "Device restricted, trying to switch to Sonos API control..."
      case DirectControl                        => "Switching to direct Spotify API control..."
    }

    msg => std.Console[F].println(stringify(msg))
  }

}
