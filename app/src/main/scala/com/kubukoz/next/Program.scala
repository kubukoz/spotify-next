package com.kubukoz.next

import cats.effect.Concurrent
import cats.effect.Sync
import cats.effect.MonadCancelThrow
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.implicits.*
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.Config.Token
import com.kubukoz.next.util.middlewares
import com.kubukoz.next.api.sonos
import fs2.io.file.Files
import monocle.Getter
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger
import java.lang.System
import java.nio.file.Paths
import fs2.io.file.Path
import cats.data.OptionT
import cats.MonadThrow
import org.http4s.implicits.*
import smithy4s.http4s.SimpleRestJsonBuilder
import com.kubukoz.next.spotify.SpotifyApiGen
import com.kubukoz.next.spotify.SpotifyApi
import com.kubukoz.next.sonos.SonosApiGen
import com.kubukoz.next.sonos.SonosApi
import org.http4s.Request
import com.kubukoz.next.util.Config.RefreshToken
import org.http4s.MediaType
import org.http4s.Charset
import org.http4s.headers.`Content-Type`
import com.kubukoz.next.Spotify.SonosInfo.Group
import com.kubukoz.next.Spotify.SonosInfo
import com.kubukoz.next.Spotify.DeviceInfo
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import fs2.io.net.Network

object Program {

  trait System[F[_]] {
    def getenv(name: String): F[Option[String]]
  }

  object System {
    def apply[F[_]](using F: System[F]): System[F] = F

    given [F[_]: Sync]: System[F] = name => Sync[F].delay(java.lang.System.getenv(name)).map(Option(_))
  }

  private def configPath[F[_]: System: MonadThrow]: F[Path] = {
    val xdgConfig = OptionT(System[F].getenv("XDG_CONFIG_HOME")).map(Path(_))
    val homeConfig = System[F]
      .getenv("HOME")
      .flatMap(_.liftTo[F](new Throwable("HOME not defined, I don't even")))
      .map(Path(_))
      .map(_.resolve(".config"))

    xdgConfig
      .getOrElseF(homeConfig)
      .map(_.resolve("spotify-next").resolve("config.json"))
  }

  def makeLoader[F[_]: Files: System: Ref.Make: UserOutput: Console: fs2.Compiler.Target]: F[ConfigLoader[F]] =
    configPath[F].flatMap { p =>
      ConfigLoader
        .cached[F]
        .compose(ConfigLoader.withCreateFileIfMissing[F](p))
        .apply(ConfigLoader.default[F](p))
    }

  def makeBasicClient[F[_]: Async: Network: Logger]: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true, logAction = Some(Logger[F].debug(_: String))))
      .map(ResponseLogger(logHeaders = true, logBody = true, logAction = Some(Logger[F].debug(_: String))))

  def apiClient[F[_]: Console: ConfigLoader: MonadCancelThrow](
    loginProcess: LoginProcess[F],
    getToken: Config => Option[Token]
  )(
    using SC: fs2.Compiler[F, F]
  ): Client[F] => Client[F] =
    middlewares
      .logFailedResponse[F]
      .compose(middlewares.retryUnauthorizedWith(loginProcess.login))
      .compose(middlewares.withToken[F](ConfigLoader[F].loadConfig.map(getToken)))

  def sonosMiddlewares[F[_]: MonadCancelThrow]: Client[F] => Client[F] =
    middlewares.defaultContentType(`Content-Type`(MediaType.application.json, Charset.`UTF-8`))

  def makeSpotify[F[_]: UserOutput: ConfigLoader: Concurrent](spotifyClient: Client[F], sonosClient: Client[F]): F[Spotify[F]] = {
    val spotifyBaseUri = com.kubukoz.next.api.spotify.baseUri

    for {
      given SpotifyApi[F] <- SimpleRestJsonBuilder(SpotifyApiGen).client[F](spotifyClient).uri(spotifyBaseUri).use.liftTo[F]
      given SonosApi[F]   <- SimpleRestJsonBuilder(SonosApiGen).client[F](sonosClient).uri(sonos.baseUri).use.liftTo[F]
      result              <- makeSpotifyInternal[F]
    } yield result
  }

  def makeSpotifyInternal[F[_]: UserOutput: SpotifyApi: SonosApi: ConfigLoader: Concurrent]: F[Spotify[F]] = {
    given Spotify.DeviceInfo[F] = Spotify.DeviceInfo.instance
    given Spotify.SonosInfo[F] = Spotify.SonosInfo.instance[F]

    given Spotify.Switch[F] = Spotify.Switch.suspend {
      DeviceInfo[F]
        .isRestricted
        .map {
          case true  => Spotify.Switch.spotifyInstance[F]
          case false => Spotify.Switch.sonosInstance[F]
        }
    }

    for {
      given Spotify.Playback[F] <- SpotifyChoice.choose[F].map(Spotify.Playback.makeFromChoice[F])
      given Analysis[F]         <- Analysis.cached(Analysis.instance[F])
    } yield Spotify.instance[F]
  }

}
