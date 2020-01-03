package com.kubukoz.next.util

import io.circe.generic.extras.Configuration
import io.circe.Codec
import io.circe.generic.extras.semiauto._

final case class Config(clientId: String, clientSecret: String, loginPort: Int, token: Config.Token)

object Config extends AskFor[Config] {
  implicit val config = Configuration.default

  val initial: Config = Config("your-client-id", "your-client-secret", 4321, Token(""))

  final case class Token(value: String) extends AnyVal

  object Token extends AskFor[Token] {
    implicit val codec: Codec[Token] = deriveUnwrappedCodec
  }

  implicit val codec: Codec[Config] = deriveConfiguredCodec
}
