package com.kubukoz.next

import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.implicits.*
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits.*
import cats.MonadThrow
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder

trait Login[F[_]] {
  def server: F[OAuth.Tokens]
  def refreshToken(token: RefreshToken): F[Token]
}

object Login {
  def apply[F[_]](using F: Login[F]): Login[F] = F

  def ember[F[_]: UserOutput: Config.Ask: Async](
    oauth: OAuth[F]
  ): Login[F] =
    new Login[F] {

      def refreshToken(token: RefreshToken): F[Token] = oauth.refreshToken(token)

      def mkServer(config: Config, route: HttpRoutes[F]) =
        EmberServerBuilder
          .default[F]
          .withHttpApp(route.orNotFound)
          .withPort(config.loginPort)
          .build

      val server: F[OAuth.Tokens] =
        (Config.ask[F], Deferred[F, OAuth.Tokens], Deferred[F, Unit])
          .tupled
          .flatMap { case (config, tokensPromise, finishServer) =>
            val finishLogin: OAuth.Code => F[Unit] = oauth.getTokens(_).flatMap(tokensPromise.complete(_).void)
            val route = Login.routes[F](finishLogin, finishServer.complete(()).void)

            // start server
            // user visits website
            // finishLogin is called and the tokens promise is fulfilled
            // user gets response
            // server shuts down
            // tokens are returned
            mkServer(config, route).use { _ =>
              oauth.getAuthorizeUri.flatMap { uri =>
                UserOutput[F].print(UserMessage.GoToUri(uri))
              } *> finishServer.get
            } *> tokensPromise.get
          }

    }

  def routes[F[_]: MonadThrow](saveCode: OAuth.Code => F[Unit], finishServer: F[Unit]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of { case req @ GET -> Root / "login" =>
      val codeF = req.uri.params.get("code").map(OAuth.Code(_)).liftTo[F](new Throwable("No code in URI!"))

      codeF.flatMap(saveCode) *>
        Ok("Login successful, you can get back to the application").map { response =>
          response.withEntity(response.body.onFinalize(finishServer))
        }
    }
  }

}
