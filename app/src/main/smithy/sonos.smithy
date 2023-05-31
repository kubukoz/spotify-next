$version: "2"

namespace com.kubukoz.next.sonos

use alloy#simpleRestJson

@simpleRestJson
service SonosApi {
    version: "0.0.0"
    operations: [
        NextTrack
        Seek
        GetHouseholds
        GetGroups
        Play
    ]
}

@http(method: "POST", uri: "/groups/{groupId}/playback/play")
operation Play {
    input := {
        @required
        @httpLabel
        groupId: GroupId
    }
}

@http(method: "POST", uri: "/groups/{groupId}/playback/skipToNextTrack")
operation NextTrack {
    input := {
        @required
        @httpLabel
        groupId: GroupId
    }
}

string GroupId

@http(method: "POST", uri: "/groups/{groupId}/playback/seek")
operation Seek {
    input := {
        @required
        @httpLabel
        groupId: GroupId
        @required
        positionMillis: Milliseconds
    }
}

integer Milliseconds

@http(method: "GET", uri: "/households")
@readonly
operation GetHouseholds {
    output := {
        @required
        households: Households
    }
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
    input := {
        @required
        @httpLabel
        householdId: HouseholdId
    }
    output := {
        @required
        groups: Groups
    }
}

list Groups {
    member: Group
}

structure Group {
    @required
    id: GroupId
    @required
    name: String
}
