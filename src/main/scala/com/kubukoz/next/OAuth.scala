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

trait OAuth[F[_]] {
  def getAuthorizeUri: F[Uri]
  def getTokens(code: OAuth.Code): F[OAuth.Tokens]
  def refreshToken(token: RefreshToken): F[Token]
}

object OAuth {
  final case class Code(value: String)
  final case class Tokens(access: Token, refresh: RefreshToken)

  def spotify[F[_]: Config.Ask: Concurrent](client: Client[F]): OAuth[F] = new OAuth[F] {

    val baseUri = uri"https://accounts.spotify.com/"

    def refreshToken(token: RefreshToken): F[Token] = {
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
        .flatMap(client.fetchAs[RefreshedTokenResponse])
        .map(_.access_token)
        .map(Token(_))
    }

    def getTokens(code: Code): F[Tokens] = Config.ask[F].flatMap { config =>
      val body = UrlForm(
        "grant_type" -> "authorization_code",
        "code" -> code.value,
        "redirect_uri" -> config.redirectUri,
        "client_id" -> config.clientId,
        "client_secret" -> config.clientSecret
      )

      client
        .expect[TokenResponse](
          Request[F](POST, baseUri / "api" / "token").withEntity(body)
        )
        .map { response =>
          Tokens(Token(response.access_token), RefreshToken(response.refresh_token))
        }
    }

    def getAuthorizeUri: F[Uri] = {
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
          uri"https://accounts.spotify.com/authorize"
            .withQueryParam("client_id", config.clientId)
            .withQueryParam("scope", scopes.mkString(" "))
            .withQueryParam("redirect_uri", config.redirectUri)
            .withQueryParam("response_type", "code")
        }
    }

  }

}
