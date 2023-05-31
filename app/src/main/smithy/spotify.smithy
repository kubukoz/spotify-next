$version: "2"

namespace com.kubukoz.next.spotify

use alloy#discriminated
use alloy#simpleRestJson

@simpleRestJson
service SpotifyApi {
    version: "0.0.0"
    operations: [
        NextTrack
        Seek
        RemoveTrack
        GetAudioAnalysis
        TransferPlayback
        GetAvailableDevices
        GetPlayer
        AddItemsToPlaylist
    ]
}

@http(method: "PUT", uri: "/v1/me/player")
@idempotent
operation TransferPlayback {
    input := {
        @jsonName("device_ids")
        @required
        deviceIds: DeviceIds
    }
}

list DeviceIds {
    member: DeviceId
}

string DeviceId

@http(method: "GET", uri: "/v1/me/player/devices")
@readonly
operation GetAvailableDevices {
    output := {
        @required
        devices: Devices
    }
}

list Devices {
    member: Device
}

structure Device {
    id: DeviceId
    @required
    name: String
    @jsonName("is_restricted")
    @required
    isRestricted: Boolean
}

@http(method: "POST", uri: "/v1/me/player/next")
operation NextTrack {

}

@http(method: "PUT", uri: "/v1/me/player/seek")
@idempotent
operation Seek {
    input := {
        @httpQuery("position_ms")
        @required
        positionMs: Integer
    }
}

@suppress(["HttpMethodSemantics"])
@http(method: "DELETE", uri: "/v1/playlists/{playlistId}/tracks")
operation RemoveTrack {
    input := {
        @httpLabel
        @required
        playlistId: String
        @required
        tracks: Tracks
    }
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
    input := {
        @httpLabel
        @required
        trackId: String
    }
    output := {
        @required
        sections: Sections
    }
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
    output := {
        context: PlayerContext
        item: PlayerItem
        @required
        @jsonName("progress_ms")
        progressMillis: Integer
        @required
        device: Device
    }
}

@discriminated("type")
union PlayerContext {
    playlist: PlaylistContext
    album: AlbumContext
    artist: ArtistContext
    collection: Unit
}

structure PlaylistContext {
    @required
    href: String
    @required
    uri: String
}

structure AlbumContext {
    @required
    href: String
}

structure ArtistContext {
    @required
    href: String
}

@discriminated("type")
union PlayerItem {
    track: TrackItem
}

structure TrackItem {
    @required
    uri: String
    @required
    @jsonName("duration_ms")
    durationMs: Integer
    @required
    name: String
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

@http(method: "POST", uri: "/v1/playlists/{playlistId}/tracks")
operation AddItemsToPlaylist {
    input := {
        @httpLabel
        @required
        playlistId: String
        @required
        @httpPayload
        uris: Uris
    }
}

@length(min: 1, max: 100)
list Uris {
    member: String
}
