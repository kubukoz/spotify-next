package com.kubukoz.next.api

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.Codec

object spotify {
  implicit val circeConfig = Configuration.default.withDiscriminator("type")

  final case class Player(context: PlayerContext)

  object Player {
    implicit val codec: Codec[Player] = deriveConfiguredCodec
  }

  sealed trait PlayerContext extends Product with Serializable

  object PlayerContext {
    final case class playlist(href: String) extends PlayerContext
    final case class album(href: String) extends PlayerContext
    final case class artist(href: String) extends PlayerContext

    implicit val codec: Codec[PlayerContext] = deriveConfiguredCodec
  }
}
