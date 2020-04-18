package com.kubukoz.next.util

import io.circe.generic.extras.Configuration
import io.circe.Codec
import io.circe.generic.extras.semiauto._

final case class Config(
  clientId: String,
  clientSecret: String,
  loginPort: Int,
  token: Option[Config.Token],
  refreshToken: Option[Config.RefreshToken]
) {
  def redirectUri: String = show"http://localhost:$loginPort/login"
}

object Config extends AskFor[Config] {
  implicit val config = Configuration.default

  val defaultPort: Int = 4321

  final case class Token(value: String) extends AnyVal

  object Token extends AskFor[Option[Token]] {
    implicit val codec: Codec[Token] = deriveUnwrappedCodec
  }

  final case class RefreshToken(value: String) extends AnyVal

  object RefreshToken {
    implicit val codec: Codec[RefreshToken] = deriveUnwrappedCodec
  }

  implicit val codec: Codec[Config] = deriveConfiguredCodec
}
