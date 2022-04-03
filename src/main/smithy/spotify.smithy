namespace com.kubukoz.next.spotify

use smithy4s.api#simpleRestJson

@simpleRestJson
service SpotifyApi {
  version: "0.0.0",
  operations: [NextTrack, Seek, RemoveTrack, GetAudioAnalysis, TransferPlayback, GetAvailableDevices]
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
  @required
  id: DeviceId,
  @required
  name: String
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