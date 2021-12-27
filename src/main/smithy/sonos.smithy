namespace com.kubukoz.next.sonos

use smithy4s.api#simpleRestJson

@simpleRestJson
service SonosApi {
  version: "0.0.0",
  operations: [NextTrack, Seek, GetZones]
}

@http(method: "POST", uri: "/{room}/next")
operation NextTrack {
  input: NextTrackInput,
}

structure NextTrackInput {
  @required
  @httpLabel
  room: String
}


@http(method: "PUT", uri: "/{room}/timeseek/{seconds}")
@idempotent
operation Seek {
  input: SeekInput,
}

structure SeekInput {
  @required
  @httpLabel
  room: String,
  @required
  @httpLabel
  seconds: Integer
}


@http(method: "GET", uri: "/zones")
@readonly
operation GetZones {
  output: GetZonesOutput
}

structure GetZonesOutput {
  @httpPayload
  @required
  zones: Zones
}

@length(min: 1)
list Zones {
  member: Zone
}

structure Zone {
  @required
  coordinator: ZoneCoordinator
}

structure ZoneCoordinator {
  @required
  roomName: String
}
