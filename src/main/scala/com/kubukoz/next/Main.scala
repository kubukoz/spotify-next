package com.kubukoz.next

import com.kubukoz.next.util.Config
import cats.effect.Console.implicits._
import cats.effect.Console.io._
import com.monovore.decline._
import com.monovore.decline.effect._

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import java.lang.System
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.HttpRoutes
import org.http4s.headers.`Content-Type`
import org.http4s.dsl.Http4sDsl
import com.kubukoz.next.util.Config.Token
import org.http4s.Uri
import scala.concurrent.duration._
import org.http4s.MediaType
import org.http4s.Charset

sealed trait Choice extends Product with Serializable

object Choice {
  val nextTrack: Opts[Choice] = Opts.subcommand("next", "Skip to next track without any changes")(Opts(NextTrack))

  val opts: Opts[Choice] =
    nextTrack
}

case object NextTrack extends Choice
case object DropTrack extends Choice
final case class FastForward(percentage: Int) extends Choice

object Main extends CommandIOApp(name = "spotify-next", header = "Gather great music") {

  val configPath = Paths.get(System.getProperty("user.home") + "/.spotify-next.json")

  import org.http4s.implicits._

  def saveToken(token: Token): IO[Unit] =
    putStrLn("Received token " + token.value.take(5) + "... dalej nie zobaczysz, nie dla psa, dla pana xD")

  def loginServer(config: Config): IO[Token] = {
    val loginTimeout = 20.minutes
    // val loginTimeout = 20.seconds
    val uri =
      Uri
        .uri("https://accounts.spotify.com/authorize")
        .withQueryParam("client_id", config.clientId)
        .withQueryParam("client_secret", config.clientSecret)
        .withQueryParam("scopes", Spotify.scopes.mkString(" "))
        .withQueryParam("redirect_uri", s"http://localhost:${config.loginPort}/login")
        .withQueryParam("response_type", "token")

    def server(tokenPromise: Deferred[IO, Token]) =
      BlazeServerBuilder[IO]
        .withHttpApp(LoginApp.routes(tokenPromise.complete).orNotFound)
        .bindHttp(port = config.loginPort)
        .resource

    Deferred[IO, Token].flatMap { tokenPromise =>
      server(tokenPromise).use { _ =>
        putStrLn(s"Go to $uri") *> tokenPromise.get.timeout(loginTimeout).onError {
          case _ => putError(s"Didn't login in $loginTimeout, exiting.")
        }
      }
    }
  }

  val loginUser = Blocker[IO].map(Files.instance[IO](configPath, _)).use { files =>
    for {
      config <- files.loadConfig.flatMap(_.ask)
      token <- loginServer(config)
      newConfig = config.copy(token = token)
      _ <- files.saveConfig(newConfig)
      _ <- putStrLn("Saved token to file")
    } yield ()
  }

  def makeClient[F[_]: ConcurrentEffect: ContextShift: Console]: Resource[F, Spotify[F]] =
    BlazeClientBuilder[F](ExecutionContext.global)
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger[F](logHeaders = true, logBody = true))
      .map(ResponseLogger[F](logHeaders = true, logBody = true))
      .flatMap { implicit client =>
        Blocker[F].map(Files.instance[F](configPath, _)).evalMap(_.loadConfig).map { implicit configAsk =>
          import com.olegpy.meow.hierarchy.deriveApplicativeAsk
          Spotify.instance[F]
        }
      }

  val runApp: Choice => Spotify[IO] => IO[Unit] = {
    case NextTrack      => _.nextTrack
    case DropTrack      => _.dropTrack
    case FastForward(p) => _.fastForward(p)
  }

  val loginCommand = Opts.subcommand("login", "Request login URL")(Opts(loginUser))

  val main: Opts[IO[ExitCode]] =
    (loginCommand <+> Choice.opts.map(runApp).map(makeClient[IO].use)).map(_.as(ExitCode.Success))
}

object LoginApp {

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
