package com.kubukoz.next.util

import com.ocadotechnology.sttp.oauth2.AuthorizationCode
import com.ocadotechnology.sttp.oauth2.AuthorizationCodeProvider
import com.ocadotechnology.sttp.oauth2.Oauth2TokenResponse
import com.ocadotechnology.sttp.oauth2.ScopeSelection
import com.ocadotechnology.sttp.oauth2.Secret
import com.ocadotechnology.sttp.oauth2.common
import io.circe.Codec
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import sttp.client.SttpBackend
import sttp.model.Uri

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

        val baseUrl = uri"https://accounts.spotify.com"

        // todo: https://github.com/ocadotechnology/sttp-oauth2/issues/9
        // need support for customizable URL paths
        val baseInstance = AuthorizationCodeProvider
          .uriInstance[F](
            baseUrl = baseUrl,
            redirectUri = config.redirectUri,
            clientId = config.clientId,
            clientSecret = config.clientSecret
          )

        new AuthorizationCodeProvider[Uri, F] {
          def loginLink(state: Option[String], scope: Set[common.Scope]): Uri =
            baseInstance.loginLink(state, scope).path("authorize")

          // not used - plain forwarder
          def logoutLink(postLogoutRedirect: Option[Uri]): Uri = baseInstance.logoutLink(postLogoutRedirect)

          // todo: this currently fails because Spotify doesn't return all the data required in Oauth2TokenResponse.
          // Available fields: access_token, token_type, expires_in, refresh_token, scope
          def authCodeToToken(authCode: String): F[Oauth2TokenResponse] =
            AuthorizationCode
              .authCodeToToken[F](
                tokenUri = baseUrl.path("api", "token"),
                redirectUri = config.redirectUri,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
                authCode = authCode
              )

          // This will suffer from the same problems as authCodeToToken.
          def refreshAccessToken(refreshToken: String, scope: ScopeSelection): F[Oauth2TokenResponse] =
            AuthorizationCode.refreshAccessToken[F](
              tokenUri = baseUrl.path("api", "token"),
              clientId = config.clientId,
              clientSecret = config.clientSecret,
              refreshToken = refreshToken,
              scopeOverride = scope
            )

        }
      }

}
