package com.kubukoz.next

import com.kubukoz.next.util.Config.Token
import com.kubukoz.next.util.Config
import cats.tagless.finalAlg
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.Uri
import org.http4s.implicits._
import scala.concurrent.duration._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.http4s.Charset

@finalAlg
trait Login[F[_]] {
  def server: F[Token]
}

object Login {

  def blaze[F[_]: ConcurrentEffect: Timer: Console: Config.Ask]: Login[F] = new Login[F] {
    val loginTimeout = 20.minutes
    // val loginTimeout = 20.seconds

    val scopes = Set(
      "playlist-read-private",
      "playlist-modify-private",
      "streaming",
      "user-read-playback-state"
    )

    private val showUri = Config.ask[F].flatMap { config =>
      val uri = Uri
        .uri("https://accounts.spotify.com/authorize")
        .withQueryParam("client_id", config.clientId)
        .withQueryParam("client_secret", config.clientSecret)
        .withQueryParam("scopes", scopes.mkString(" "))
        .withQueryParam("redirect_uri", s"http://localhost:${config.loginPort}/login")
        .withQueryParam("response_type", "token")

      Console[F].putStrLn(s"Go to $uri(config)")
    }

    def mkServer(tokenPromise: Deferred[F, Token]) = Resource.suspend {
      Config.ask[F].map { config =>
        BlazeServerBuilder[F]
          .withHttpApp(Login.routes(tokenPromise.complete).orNotFound)
          .bindHttp(port = config.loginPort)
          .resource
      }
    }

    val server: F[Token] =
      Deferred[F, Token].flatMap { tokenPromise =>
        mkServer(tokenPromise).use { _ =>
          showUri *> tokenPromise.get.timeout(loginTimeout).onError {
            case _ => Console[F].putError(s"Didn't login in $loginTimeout, exiting.")
          }
        }
      }
  }

  def routes[F[_]: Sync](saveToken: Token => F[Unit]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    val html = `Content-Type`(MediaType.text.html, Charset.`UTF-8`)

    HttpRoutes.of {
      case GET -> Root / "login" =>
        // Servers aren't allowed to read query fragments. So we do it on the client!
        Ok("""<!doctype html><html>
             |<script>
             |window.location = "/login_server?" + window.location.hash.slice(1)
             |</script>
             |</html>""".stripMargin).map(_.withContentType(html))
      case req @ GET -> Root / "login_server" =>
        val tokenF = req.uri.params.get("access_token").map(Token(_)).liftTo[F](new Throwable("No token in URI!"))

        tokenF.flatMap { token =>
          Ok("Login successful, you can get back to the application").map { response =>
            response.withEntity(response.body.onFinalize(saveToken(token)))
          }
        }
    }
  }
}
