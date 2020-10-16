package com.kubukoz.next

import com.kubukoz.next.util.Config
import cats.tagless.finalAlg
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.Uri
import org.http4s.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import com.kubukoz.next.util.Config.Token
import org.http4s.client.Client
import org.http4s.Method.POST
import org.http4s.Request
import com.kubukoz.next.api.spotify.TokenResponse
import com.kubukoz.next.api.spotify.RefreshedTokenResponse
import org.http4s.UrlForm
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.Login.Tokens
import org.http4s.circe.CirceEntityCodec._
import org.http4s.headers.Authorization
import org.http4s.BasicCredentials

@finalAlg
trait Login[F[_]] {
  def server: F[Tokens]
  def refreshToken(token: RefreshToken): F[Token]
}

object Login {
  final case class Tokens(access: Token, refresh: RefreshToken)

  final case class Code(value: String)

  def blaze[F[_]: ConcurrentEffect: Timer: Console: Config.Ask](client: Client[F]): Login[F] =
    new Login[F] {

      def refreshToken(token: RefreshToken): F[Token] = {
        val body = UrlForm(
          "grant_type" -> "refresh_token",
          "refresh_token" -> token.value
        )

        Config
          .ask[F]
          .map { config =>
            Request[F](POST, Uri.uri("https://accounts.spotify.com/api/token"))
              .withEntity(body)
              .putHeaders(Authorization(BasicCredentials(config.clientId, config.clientSecret)))
          }
          .flatMap(client.fetchAs[RefreshedTokenResponse])
          .map(_.accessToken)
          .map(Token(_))
      }

      val scopes = Set(
        "playlist-read-private",
        "playlist-modify-private",
        "playlist-modify-public",
        "streaming",
        "user-read-playback-state"
      )

      private val showUri = Config
        .ask[F]
        .map { config =>
          Uri
            .uri("https://accounts.spotify.com/authorize")
            .withQueryParam("client_id", config.clientId)
            .withQueryParam("client_secret", config.clientSecret)
            .withQueryParam("scope", scopes.mkString(" "))
            .withQueryParam("redirect_uri", config.redirectUri)
            .withQueryParam("response_type", "code")
        }
        .flatMap { uri =>
          Console[F].putStrLn(s"Go to $uri")
        }

      def mkServer(config: Config, route: HttpRoutes[F]) =
        BlazeServerBuilder[F]
          .withHttpApp(route.orNotFound)
          .bindHttp(port = config.loginPort)
          .resource

      def getTokens(code: Code, config: Config): F[Tokens] = {

        val body = UrlForm(
          "grant_type" -> "authorization_code",
          "code" -> code.value,
          "redirect_uri" -> config.redirectUri,
          "client_id" -> config.clientId,
          "client_secret" -> config.clientSecret
        )

        client
          .expect[TokenResponse](Request[F](POST, Uri.uri("https://accounts.spotify.com/api/token")).withEntity(body))
          .map { response =>
            Tokens(Token(response.accessToken), RefreshToken(response.refreshToken))
          }
      }

      val server: F[Tokens] =
        (Config.ask[F], Deferred[F, Tokens], Deferred[F, Unit]).tupled.flatMap {
          case (config, tokensPromise, finishServer) =>
            val finishLogin: Code => F[Unit] = getTokens(_, config).flatMap(tokensPromise.complete)
            val route = Login.routes[F](finishLogin, finishServer.complete(()))

            // start server
            // user visits spotify website
            // finishLogin is called and the tokens promise is fulfilled
            // user gets response
            // server shuts down
            // tokens are returned
            mkServer(config, route).use { _ =>
              showUri *> finishServer.get
            } *> tokensPromise.get
        }

    }

  def routes[F[_]: Sync](saveCode: Code => F[Unit], finishServer: F[Unit]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of {
      case req @ GET -> Root / "login" =>
        val codeF = req.uri.params.get("code").map(Code(_)).liftTo[F](new Throwable("No code in URI!"))

        codeF.flatMap(saveCode) *>
          Ok("Login successful, you can get back to the application").map { response =>
            response.withEntity(response.body.onFinalize(finishServer))
          }
    }
  }

}
