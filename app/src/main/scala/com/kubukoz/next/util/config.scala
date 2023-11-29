package com.kubukoz.next.util

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import cats.implicits.*
import monocle.Lens
import com.comcast.ip4s.*
import io.circe.syntax.*
import com.kubukoz.next.client.spotify.PlaylistUri

final case class Config(
  clientId: String,
  clientSecret: String,
  sonosClientId: String,
  sonosClientSecret: String,
  loginPort: Port,
  token: Option[Config.Token],
  refreshToken: Option[Config.RefreshToken],
  sonosToken: Option[Config.Token],
  sonosRefreshToken: Option[Config.RefreshToken]
) derives Codec.AsObject {
  def redirectUri: String = show"http://localhost:$loginPort/login"

  // https://open.spotify.com/playlist/3Xzu9f7hVoT8udYlxHvaoK?si=535de7cf02244ab1
  // todo: make customizable
  def targetPlaylist: PlaylistUri = PlaylistUri("3Xzu9f7hVoT8udYlxHvaoK", user = None)
}

object Config extends AskFor[Config] {
  val defaultPort: Port = port"4321"

  given Codec[Port] = Codec.from(Decoder[Int].emap(Port.fromInt(_).toRight("Couldn't parse port")), _.value.asJson)

  val spotifyTokensLens: Lens[
    Config,
    (
      Option[Token],
      Option[RefreshToken]
    )
  ] =
    Lens[
      Config,
      (
        Option[Token],
        Option[RefreshToken]
      )
    ](cfg => (cfg.token, cfg.refreshToken)) { case (token, refreshToken) =>
      _.copy(token = token, refreshToken = refreshToken)
    }

  val sonosTokensLens: Lens[
    Config,
    (
      Option[Token],
      Option[RefreshToken]
    )
  ] =
    Lens[
      Config,
      (
        Option[Token],
        Option[RefreshToken]
      )
    ](cfg => (cfg.sonosToken, cfg.sonosRefreshToken)) { case (token, refreshToken) =>
      _.copy(sonosToken = token, sonosRefreshToken = refreshToken)
    }

  final case class Token(
    value: String
  ) extends AnyVal

  object Token {
    given Codec[Token] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

  final case class RefreshToken(
    value: String
  ) extends AnyVal

  object RefreshToken {
    given Codec[RefreshToken] = Codec.from(Decoder[String].map(apply), Encoder[String].contramap(_.value))
  }

}
