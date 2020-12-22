package com.kubukoz.next

import scala.concurrent.ExecutionContext

import cats.tagless.finalAlg
import com.kubukoz.next.Login.Tokens
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import com.ocadotechnology.sttp.oauth2.AuthorizationCodeProvider
import com.ocadotechnology.sttp.oauth2.ScopeSelection
import com.ocadotechnology.sttp.oauth2.common.Scope
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import sttp.model.Uri

@finalAlg
trait Login[F[_]] {
  def server: F[Tokens]
  def refreshToken(token: RefreshToken): F[Token]
}

object Login {
  final case class Tokens(access: Token, refresh: RefreshToken)

  final case class Code(value: String)

  def blaze[F[_]: ConcurrentEffect: Timer: Console: Config.Ask: AuthorizationCodeProvider[Uri, *[_]]](
    implicit executionContext: ExecutionContext
  ): Login[F] =
    new Login[F] {

      def refreshToken(token: RefreshToken): F[Token] =
        AuthorizationCodeProvider[Uri, F].refreshAccessToken(token.value, ScopeSelection.KeepExisting).map { response =>
          Token(response.accessToken)
        }

      val scopes = Set[Scope](
        "playlist-read-private",
        "playlist-modify-private",
        "playlist-modify-public",
        "streaming",
        "user-read-playback-state"
      )

      private val showUri = {
        // todo: https://github.com/ocadotechnology/sttp-oauth2/issues/9
        val uri = AuthorizationCodeProvider[Uri, F].loginLink(scope = scopes).path("authorize")

        Console[F].putStrLn(s"Go to $uri")
      }

      def mkServer(config: Config, route: HttpRoutes[F]) = {
        import org.http4s.implicits._

        BlazeServerBuilder[F](executionContext)
          .withHttpApp(route.orNotFound)
          .bindHttp(port = config.loginPort)
          .resource
      }

      def getTokens(code: Code, config: Config): F[Tokens] =
        AuthorizationCodeProvider[Uri, F].authCodeToToken(code.value).map { response =>
          Tokens(Token(response.accessToken), RefreshToken(response.refreshToken))
        }

      val server: F[Tokens] =
        (Config.ask[F], Deferred[F, Tokens], Deferred[F, Unit])
          .tupled
          .flatMap { case (config, tokensPromise, finishServer) =>
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

    HttpRoutes.of { case req @ GET -> Root / "login" =>
      val codeF = req.uri.params.get("code").map(Code(_)).liftTo[F](new Throwable("No code in URI!"))

      codeF.flatMap(saveCode) *>
        Ok("Login successful, you can get back to the application").map { response =>
          response.withEntity(response.body.onFinalize(finishServer))
        }
    }
  }

}
