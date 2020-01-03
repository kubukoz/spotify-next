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

object Program {
  val configPath = Paths.get(System.getProperty("user.home")).resolve(".spotify-next.json")

  def makeLoader[F[_]: Sync: ContextShift: Console] = Blocker[F].evalMap { blocker =>
    ConfigLoader
      .cached[F]
      .compose(ConfigLoader.withCreateFileIfMissing[F](configPath))
      .apply(ConfigLoader.default[F](configPath, blocker))
  }

  def makeClient[F[_]: ConcurrentEffect: ContextShift: Timer: Console: ConfigLoader: Login]: Resource[F, Client[F]] =
    BlazeClientBuilder(ExecutionContext.global)
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true))
      .map(ResponseLogger(logHeaders = true, logBody = true))
      .map(middlewares.withToken)
      .map(middlewares.retryUnauthorizedWith(loginUser[F]))
      .map(middlewares.implicitHost("api.spotify.com"))
      .map(middlewares.logFailedResponse)

  // Do NOT move this into Spotify, it'll vastly increase the range of its responsibilities!
  def loginUser[F[_]: Console: Login: ConfigLoader: Monad]: F[Unit] =
    for {
      token <- Login[F].server
      config <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = token)
      _ <- ConfigLoader[F].saveConfig(newConfig)
      _ <- Console[F].putStrLn("Saved token to file")
    } yield ()

  def makeSpotify[F[_]: Console: Sync: Config.Ask](client: Client[F]) = {
    implicit val theClient = client

    Spotify.instance[F]
  }

}
