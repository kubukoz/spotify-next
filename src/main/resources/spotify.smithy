namespace com.kubukoz.next.spotify

use smithy4s.api#simpleRestJson

@simpleRestJson
service SpotifyApi {
  version: "0.0.0",
  operations: [GetPlayer]
}

@http(method: "GET", uri: "/v1/me/player")
operation GetPlayer {
  output: GetPlayerOutput
}

structure GetPlayerOutput {

}
