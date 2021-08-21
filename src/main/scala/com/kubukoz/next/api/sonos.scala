package com.kubukoz.next.api

import cats.data.NonEmptyList
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits.*
import org.http4s.Uri

object sonos {

  // Less than ideal, might end up in a Sonos[F] or something
  val baseUri: Uri = {
    import org.http4s.implicits.*
    uri"http://localhost:5005"
  }

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
