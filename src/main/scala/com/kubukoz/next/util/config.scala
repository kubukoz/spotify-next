package com.kubukoz.next.util

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits.*
import monocle.Lens

final case class Config(
  clientId: String,
  clientSecret: String,
  sonosClientId: String,
  sonosClientSecret: String,
  loginPort: Int,
  token: Option[Config.Token],
  refreshToken: Option[Config.RefreshToken],
  sonosToken: Option[Config.Token],
  sonosRefreshToken: Option[Config.RefreshToken]
) derives Codec.AsObject {
  def redirectUri: String = show"http://localhost:$loginPort/login"
}

object Config extends AskFor[Config] {
  val defaultPort: Int = 4321

  val spotifyTokensLens: Lens[Config, (Option[Token], Option[RefreshToken])] =
    Lens[Config, (Option[Token], Option[RefreshToken])](cfg => (cfg.token, cfg.refreshToken)) { case (token, refreshToken) =>
      _.copy(token = token, refreshToken = refreshToken)
    }

  val sonosTokensLens: Lens[Config, (Option[Token], Option[RefreshToken])] =
    Lens[Config, (Option[Token], Option[RefreshToken])](cfg => (cfg.sonosToken, cfg.sonosRefreshToken)) { case (token, refreshToken) =>
      _.copy(sonosToken = token, sonosRefreshToken = refreshToken)
    }

  final case class Token(value: String) extends AnyVal

  object Token {
    given Codec[Token] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

  final case class RefreshToken(value: String) extends AnyVal

  object RefreshToken {
    given Codec[RefreshToken] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

}
