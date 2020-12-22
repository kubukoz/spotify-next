package com.kubukoz.next

import com.kubukoz.next.util.middlewares

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext
import java.nio.file.Paths
import java.lang.System
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger

import org.http4s.client.Client
import com.kubukoz.next.util.Config
import com.olegpy.meow.hierarchy.deriveApplicativeAsk
import ConfigLoader.deriveAskFromLoader
import com.kubukoz.next.util.Config.RefreshToken

object Program {
  val configPath = Paths.get(System.getProperty("user.home")).resolve(".spotify-next.json")

  def makeLoader[F[_]: Sync: ContextShift: Console](blocker: Blocker) =
    ConfigLoader
      .cached[F]
      .compose(ConfigLoader.withCreateFileIfMissing[F](configPath))
      .apply(ConfigLoader.default[F](configPath, blocker))

  def makeBasicClient[F[_]: ConcurrentEffect: ContextShift: Timer]: Resource[F, Client[F]] =
    BlazeClientBuilder(ExecutionContext.global)
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true))
      .map(ResponseLogger(logHeaders = true, logBody = true))

  def apiClient[F[_]: Sync: Console: ConfigLoader: Login]: Client[F] => Client[F] = {
    val loginOrRefreshToken: F[Unit] =
      Config
        .ask[F]
        .map(_.refreshToken)
        .flatMap {
          case None               => loginUser
          case Some(refreshToken) => refreshUserToken(refreshToken)
        }

    middlewares
      .logFailedResponse[F]
      .compose(middlewares.implicitHost("api.spotify.com"))
      .compose(middlewares.retryUnauthorizedWith(loginOrRefreshToken))
      .compose(middlewares.withToken[F])
  }

  // Do NOT move this into Spotify, it'll vastly increase the range of its responsibilities!
  def loginUser[F[_]: Console: Login: ConfigLoader: Monad]: F[Unit] =
    for {
      tokens <- Login[F].server
      config <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = tokens.access.some, refreshToken = tokens.refresh.some)
      _      <- ConfigLoader[F].saveConfig(newConfig)
      _      <- Console[F].putStrLn("Saved token to file")
    } yield ()

  def refreshUserToken[F[_]: Console: Login: ConfigLoader: MonadError[*[_], Throwable]](
    refreshToken: RefreshToken
  ): F[Unit] =
    for {
      newToken <- Login[F].refreshToken(refreshToken)
      config   <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = newToken.some)
      _        <- ConfigLoader[F].saveConfig(newConfig)
      _        <- Console[F].putStrLn("Refreshed token") //todo debug level?
    } yield ()

  def makeSpotify[F[_]: Console: Sync: Config.Ask](client: Client[F]) = {
    implicit val theClient = client

    Spotify.instance[F]
  }

}
