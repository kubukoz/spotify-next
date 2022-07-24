namespace com.kubukoz.next.spotify

use smithy4s.api#simpleRestJson
use com.kubukoz.next.sonos#GetGroupsOutput
use smithy4s.api#discriminated

@simpleRestJson
service SpotifyApi {
  version: "0.0.0",
  operations: [NextTrack, Seek, RemoveTrack, GetAudioAnalysis, TransferPlayback, GetAvailableDevices, GetPlayer]
}

@http(method: "PUT", uri: "/v1/me/player")
@idempotent
operation TransferPlayback {
  input: TransferPlaybackInput
}

structure TransferPlaybackInput {
  @jsonName("device_ids")
  @required
  deviceIds: DeviceIds
}

list DeviceIds {
  member: DeviceId
}

string DeviceId

@http(method: "GET", uri: "/v1/me/player/devices")
@readonly
operation GetAvailableDevices {
  output: GetAvailableDevicesOutput
}

structure GetAvailableDevicesOutput {
  @required
  devices: Devices
}

list Devices {
  member: Device
}

structure Device {
  id: DeviceId,
  @required
  name: String,
  @jsonName("is_restricted")
  @required
  isRestricted: Boolean,
}

@http(method: "POST", uri: "/v1/me/player/next")
operation NextTrack {}

@http(method: "PUT", uri: "/v1/me/player/seek")
@idempotent
operation Seek {
  input: SeekInput
}

structure SeekInput {
  @httpQuery("position_ms")
  @required
  positionMs: Integer
}

@suppress(["HttpMethodSemantics"])
@http(method: "DELETE", uri: "/v1/playlists/{playlistId}/tracks")
operation RemoveTrack {
  input: RemoveTrackInput
}

structure RemoveTrackInput {
  @httpLabel
  @required
  playlistId: String,
  @required
  tracks: Tracks
}

list Tracks {
  member: Track
}

structure Track {
  @required
  uri: String
}


@http(method: "GET", uri: "/v1/audio-analysis/{trackId}")
@readonly
operation GetAudioAnalysis {
  input: AudioAnalysisInput,
  output: AudioAnalysis
}

structure AudioAnalysisInput {
  @httpLabel
  @required
  trackId: String
}

structure AudioAnalysis {
  @required
  sections: Sections
}

list Sections {
  member: Section
}

structure Section {
  @required
  @jsonName("start")
  startSeconds: Double
}

@http(method: "GET", uri: "/v1/me/player")
@readonly
operation GetPlayer {
  output: GetPlayerOutput
}

structure GetPlayerOutput {
  context: PlayerContext,
  item: PlayerItem,
  @required
  @jsonName("progress_ms")
  progressMillis: Integer,
  @required
  device: Device
}

@discriminated("type")
union PlayerContext {
  playlist: PlaylistContext,
  album: AlbumContext,
  artist: ArtistContext,
}

structure PlaylistContext {
  @required
  href: String,
  @required
  uri: String
}

structure AlbumContext {
  @required
  href: String,
}

structure ArtistContext {
  @required
  href: String,
}

@discriminated("type")
union PlayerItem {
  track: TrackItem,
}

structure TrackItem {
  @required
  uri: String,

  @required
  @jsonName("duration_ms")
  durationMs: Integer,

  @required
  name: String,

  @required
  artists: Artists
}

list Artists {
  member: Artist
}

structure Artist {
  @required
  name: String
}
