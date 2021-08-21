package com.kubukoz.next.util

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits.*

final case class Config(
  clientId: String,
  clientSecret: String,
  loginPort: Int,
  token: Option[Config.Token],
  refreshToken: Option[Config.RefreshToken]
) derives Codec.AsObject {
  def redirectUri: String = show"http://localhost:$loginPort/login"
}

object Config extends AskFor[Config] {
  val defaultPort: Int = 4321

  final case class Token(value: String) extends AnyVal

  object Token extends AskFor[Option[Token]] {
    given Codec[Token] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

  final case class RefreshToken(value: String) extends AnyVal

  object RefreshToken {
    given Codec[RefreshToken] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

}
