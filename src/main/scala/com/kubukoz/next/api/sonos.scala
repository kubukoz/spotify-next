package com.kubukoz.next.api

import cats.data.NonEmptyList
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits._
import org.http4s.Uri

object sonos {

  // Less than ideal, might end up in a Sonos[F] or something
  val baseUri: Uri = {
    import org.http4s.implicits._
    uri"http://localhost:5005"
  }

  final case class SonosZones(zones: NonEmptyList[Zone])

  object SonosZones {

    implicit val codec: Codec[SonosZones] =
      Codec
        .from(Decoder[NonEmptyList[Zone]], Encoder[NonEmptyList[Zone]])
        .imap(SonosZones(_))(_.zones)

  }

  final case class Zone(coordinator: Coordinator)

  object Zone {
    implicit val codec: Codec[Zone] = Codec.forProduct1("coordinator")(Zone(_))(_.coordinator)
  }

  final case class Coordinator(roomName: String)

  object Coordinator {
    implicit val codec: Codec[Coordinator] = Codec.forProduct1("roomName")(Coordinator(_))(_.roomName)
  }

}
