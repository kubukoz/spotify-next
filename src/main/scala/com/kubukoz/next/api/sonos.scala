package com.kubukoz.next.api

import cats.data.NonEmptyList
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits.*

object sonos {
  final case class SonosZones(zones: NonEmptyList[Zone])

  object SonosZones {

    given Codec[SonosZones] =
      Codec
        .from(Decoder[NonEmptyList[Zone]], Encoder[NonEmptyList[Zone]])
        .imap(SonosZones(_))(_.zones)

  }

  final case class Zone(coordinator: Coordinator) derives Codec.AsObject

  final case class Coordinator(roomName: String) derives Codec.AsObject
}
