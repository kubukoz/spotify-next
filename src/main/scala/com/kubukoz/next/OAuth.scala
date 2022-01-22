package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.api.spotify.RefreshedTokenResponse
import com.kubukoz.next.api.spotify.TokenResponse
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import org.http4s.BasicCredentials
import org.http4s.Method.POST
import org.http4s.Request
import org.http4s.Uri
import org.http4s.UrlForm
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import cats.effect.Concurrent
import cats.Monad

trait OAuth[F[_]] {
  def getAuthorizeUri: F[Uri]
  def getTokens(code: OAuth.Code): F[OAuth.Tokens]
  def refreshToken(token: RefreshToken): F[Token]
}

object OAuth {
  final case class Code(value: String)
  final case class Tokens(access: Token, refresh: RefreshToken)

  trait Kernel[F[_]] {
    def getAuthorizeUri: F[Uri]
    def getTokens(code: OAuth.Code): F[Request[F]]
    def refreshToken(token: RefreshToken): F[Request[F]]
  }

  def fromKernel[F[_]: Config.Ask: Concurrent](client: Client[F], kernel: Kernel[F]): OAuth[F] = new OAuth[F] {

    private val baseUri = uri"https://accounts.spotify.com"

    def refreshToken(token: RefreshToken): F[Token] =
      client
        .fetchAs[RefreshedTokenResponse](kernel.refreshToken(token))
        .map(_.access_token)
        .map(Token(_))

    def getTokens(code: Code): F[Tokens] =
      client
        .expect[TokenResponse](kernel.getTokens(code))
        .map { response =>
          Tokens(Token(response.access_token), RefreshToken(response.refresh_token))
        }

    val getAuthorizeUri: F[Uri] = kernel.getAuthorizeUri
  }

  def spotify[F[_]: Config.Ask: Concurrent]: OAuth.Kernel[F] = new OAuth.Kernel[F] {

    private val baseUri = uri"https://accounts.spotify.com"

    def refreshToken(token: RefreshToken): F[Request[F]] = {
      val body = UrlForm(
        "grant_type" -> "refresh_token",
        "refresh_token" -> token.value
      )

      Config
        .ask[F]
        .map { config =>
          Request[F](POST, baseUri / "api" / "token")
            .withEntity(body)
            .putHeaders(Authorization(BasicCredentials(config.clientId, config.clientSecret)))
        }
    }

    def getTokens(code: Code): F[Request[F]] = Config.ask[F].map { config =>
      Request[F](POST, baseUri / "api" / "token").withEntity(
        UrlForm(
          "grant_type" -> "authorization_code",
          "code" -> code.value,
          "redirect_uri" -> config.redirectUri,
          "client_id" -> config.clientId,
          "client_secret" -> config.clientSecret
        )
      )
    }

    val getAuthorizeUri: F[Uri] = {
      val scopes = Set(
        "playlist-read-private",
        "playlist-modify-private",
        "playlist-modify-public",
        "streaming",
        "user-read-playback-state"
      )

      Config
        .ask[F]
        .map { config =>
          (baseUri / "authorize")
            .withQueryParam("client_id", config.clientId)
            .withQueryParam("scope", scopes.mkString(" "))
            .withQueryParam("redirect_uri", config.redirectUri)
            .withQueryParam("response_type", "code")
        }
    }

  }

  def sonos[F[_]: Config.Ask: Monad]: OAuth.Kernel[F] = new OAuth.Kernel[F] {
    private val baseUri = uri"https://api.sonos.com"

    def refreshToken(token: RefreshToken): F[Request[F]] =
      Config
        .ask[F]
        .map { config =>
          Request[F](POST, baseUri / "login" / "v3" / "oauth" / "access")
            .withEntity(
              UrlForm(
                "grant_type" -> "refresh_token",
                "refresh_token" -> token.value
              )
            )
            .putHeaders(Authorization(BasicCredentials(config.sonosClientId, config.sonosClientSecret)))
        }

    def getTokens(code: Code): F[Request[F]] = Config.ask[F].map { config =>
      Request[F](POST, baseUri / "login" / "v3" / "oauth" / "access")
        .withEntity(
          UrlForm(
            "grant_type" -> "authorization_code",
            "code" -> code.value,
            "redirect_uri" -> config.redirectUri
          )
        )
        .putHeaders(Authorization(BasicCredentials(config.sonosClientId, config.sonosClientSecret)))
    }

    val getAuthorizeUri: F[Uri] =
      Config
        .ask[F]
        .map { config =>
          (baseUri / "login" / "v3" / "oauth")
            .withQueryParam("client_id", config.sonosClientId)
            .withQueryParam(
              "scope",
              Set(
                "playback-control-all"
              ).mkString(" ")
            )
            .withQueryParam("redirect_uri", config.redirectUri)
            .withQueryParam("response_type", "code")
            .withQueryParam("state", "demo")
        }

  }

}
