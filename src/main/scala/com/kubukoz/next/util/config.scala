package com.kubukoz.next.util

import io.circe.generic.extras.Configuration
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import sttp.client.SttpBackend
import com.ocadotechnology.sttp.oauth2.AuthorizationCodeProvider
import com.ocadotechnology.sttp.oauth2.Secret
import sttp.model.Uri
import io.circe.Encoder

final case class Config(
  clientId: String,
  clientSecret: Secret[String],
  loginPort: Int,
  token: Option[Config.Token],
  refreshToken: Option[Config.RefreshToken]
) {

  def redirectUri: Uri = {
    import sttp.client._
    uri"http://localhost:$loginPort/login"
  }

}

object Config extends AskFor[Config] {
  implicit val config = Configuration.default

  val defaultPort: Int = 4321

  final case class Token(value: Secret[String]) extends AnyVal {
    def stringValue: String = value.value.trim
  }

  object Token extends AskFor[Option[Token]] {
    implicit val codec: Codec[Token] = deriveUnwrappedCodec
  }

  final case class RefreshToken(value: String) extends AnyVal

  object RefreshToken {
    implicit val codec: Codec[RefreshToken] = deriveUnwrappedCodec
  }

  //todo https://github.com/ocadotechnology/sttp-oauth2/issues/10
  implicit def secretEncoder[A: Encoder]: Encoder[Secret[A]] =
    Encoder[A].contramap(_.value)

  implicit val codec: Codec[Config] = deriveConfiguredCodec

  def buildTokenProvider[F[_]: Config.Ask: MonadThrow](
    backend: SttpBackend[F, Nothing, Nothing]
  ): F[AuthorizationCodeProvider[Uri, F]] =
    Config
      .ask[F]
      .map { config =>
        import sttp.client._
        implicit val backendImplicit: SttpBackend[F, Nothing, Nothing] = backend

        AuthorizationCodeProvider
          .uriInstance[F](
            baseUrl = uri"https://accounts.spotify.com",
            redirectUri = config.redirectUri,
            clientId = config.clientId,
            clientSecret = config.clientSecret
          )
      }

}
