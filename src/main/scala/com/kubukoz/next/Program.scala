package com.kubukoz.next

import cats.Monad
import cats.MonadError
import cats.effect.Concurrent
import cats.effect.MonadCancelThrow
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.implicits._
import com.kubukoz.next.api.sonos
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import com.kubukoz.next.util.middlewares
import fs2.io.file.Files
import monocle.Getter
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
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

  def apiClient[F[_]: UserOutput: Console: ConfigLoader: Login: MonadCancelThrow](
    implicit SC: fs2.Compiler[F, F]
  ): Client[F] => Client[F] = {
    implicit val configAsk: Config.Ask[F] = ConfigLoader[F].configAsk
    implicit val tokenAsk: Token.Ask[F] = Token.askBy(configAsk)(Getter(_.token))

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
      .compose(middlewares.retryUnauthorizedWith(loginOrRefreshToken))
      .compose(middlewares.withToken[F])
  }

  // Do NOT move this into Spotify, it'll vastly increase the range of its responsibilities!
  def loginUser[F[_]: UserOutput: Login: ConfigLoader: Monad]: F[Unit] =
    for {
      tokens <- Login[F].server
      config <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = tokens.access.some, refreshToken = tokens.refresh.some)
      _      <- ConfigLoader[F].saveConfig(newConfig)
      _      <- UserOutput[F].print(UserMessage.SavedToken)
    } yield ()

  def refreshUserToken[F[_]: UserOutput: Login: ConfigLoader: MonadError[*[_], Throwable]](
    refreshToken: RefreshToken
  ): F[Unit] =
    for {
      newToken <- Login[F].refreshToken(refreshToken)
      config   <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = newToken.some)
      _        <- ConfigLoader[F].saveConfig(newConfig)
      _        <- UserOutput[F].print(UserMessage.RefreshedToken)
    } yield ()

  def makeSpotify[F[_]: UserOutput: Concurrent](client: Client[F]): F[Spotify[F]] = {
    val sonosUri = sonos.baseUri

    implicit val theClient = client
    implicit val deviceInfo = Spotify.DeviceInfo.instance(client)
    implicit val sonosInfo = Spotify.SonosInfo.instance(sonosUri, client)

    Spotify
      .Playback
      .build[F, Spotify.Playback[F]](
        Spotify.Playback.sonosInstance[F](sonosUri, client),
        Spotify.Playback.spotifyInstance[F](client)
      )
      .map(Spotify.Playback.suspend(_))
      .map { implicit playback =>
        Spotify.instance[F]
      }
  }

}
