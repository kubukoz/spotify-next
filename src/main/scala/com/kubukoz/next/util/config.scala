package com.kubukoz.next.util

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

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
  val defaultPort: Int = 4321

  final case class Token(value: String) extends AnyVal

  object Token extends AskFor[Option[Token]] {
    implicit val codec: Codec[Token] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

  final case class RefreshToken(value: String) extends AnyVal

  object RefreshToken {
    implicit val codec: Codec[RefreshToken] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

  implicit val codec: Codec[Config] =
    Codec.forProduct5(
      "clientId",
      "clientSecret",
      "loginPort",
      "token",
      "refreshToken"
    )(apply)(unapply(_).get)

}
