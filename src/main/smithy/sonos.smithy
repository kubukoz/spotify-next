namespace com.kubukoz.next.sonos

use smithy4s.api#simpleRestJson

@simpleRestJson
service SonosApi {
  version: "0.0.0",
  operations: [NextTrack, Seek, GetHouseholds, GetGroups, Play]
}

@http(method: "POST", uri: "/groups/{groupId}/playback/play")
operation Play {
  input: PlayInput
}

structure PlayInput {
  @required
  @httpLabel
  groupId: GroupId
}

@http(method: "POST", uri: "/groups/{groupId}/playback/skipToNextTrack")
operation NextTrack {
  input: NextTrackInput,
}

structure NextTrackInput {
  @required
  @httpLabel
  groupId: GroupId,
}

string GroupId


@http(method: "POST", uri: "/groups/{groupId}/playback/seek")
operation Seek {
  input: SeekInput,
}

structure SeekInput {
  @required
  @httpLabel
  groupId: GroupId,
  @required
  @httpPayload
  body: SeekInputBody
}

structure SeekInputBody {
  @required
  positionMillis: Milliseconds
}

integer Milliseconds

@http(method: "GET", uri: "/households")
@readonly
operation GetHouseholds {
  output: GetHouseholdsOutput
}

structure GetHouseholdsOutput {
  @required
  households: Households
}

list Households {
  member: Household
}

structure Household {
  @required
  id: HouseholdId
}

string HouseholdId

@http(method: "GET", uri: "/households/{householdId}/groups")
@readonly
operation GetGroups {
  input: GetGroupsInput,
  output: GetGroupsOutput
}

structure GetGroupsInput {
  @required
  @httpLabel
  householdId: HouseholdId,
}

structure GetGroupsOutput {
  @required
  groups: Groups
}

list Groups {
  member: Group
}

structure Group {
  @required
  id: GroupId,
  @required
  name: String
}
