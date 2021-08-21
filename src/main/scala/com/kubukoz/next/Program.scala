package com.kubukoz.next

import cats.effect.Concurrent
import cats.effect.MonadCancelThrow
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.implicits.*
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.Token
import com.kubukoz.next.util.middlewares
import fs2.io.file.Files
import monocle.Getter
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger
import java.lang.System
import java.nio.file.Paths

object Program {
  val configPath = Paths.get(System.getProperty("user.home")).resolve(".spotify-next.json")

  def makeLoader[F[_]: Files: Ref.Make: UserOutput: Console: fs2.Compiler.Target]: F[ConfigLoader[F]] =
    ConfigLoader
      .cached[F]
      .compose(ConfigLoader.withCreateFileIfMissing[F](configPath))
      .apply(ConfigLoader.default[F](configPath))

  def makeBasicClient[F[_]: Async]: Resource[F, Client[F]] =
    Resource
      .eval(Async[F].executionContext)
      .flatMap { ec =>
        BlazeClientBuilder(ec).resource
      }
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true))
      .map(ResponseLogger(logHeaders = true, logBody = true))

  def apiClient[F[_]: Console: ConfigLoader: LoginProcess: MonadCancelThrow](
    using SC: fs2.Compiler[F, F]
  ): Client[F] => Client[F] = {
    given ca: Config.Ask[F] = ConfigLoader[F].configAsk
    given ta: Token.Ask[F] = Token.askBy(ca)(Getter(_.token))

    val loginOrRefreshToken: F[Unit] =
      Config
        .ask[F]
        .map(_.refreshToken)
        .flatMap {
          case None               => LoginProcess[F].login
          case Some(refreshToken) => LoginProcess[F].refreshUserToken(refreshToken)
        }

    middlewares
      .logFailedResponse[F]
      .compose(middlewares.retryUnauthorizedWith(loginOrRefreshToken))
      .compose(middlewares.withToken[F])
  }

  def makeSpotify[F[_]: UserOutput: Concurrent](client: Client[F]): Spotify[F] = {
    given Client[F] = client
    given Spotify.Playback[F] = Spotify.Playback.spotify[F](client)

    Spotify.instance[F]
  }

}
