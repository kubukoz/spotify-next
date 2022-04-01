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
import org.http4s.blaze.client.BlazeClientBuilder
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

  def makeBasicClient[F[_]: Async]: Resource[F, Client[F]] =
    BlazeClientBuilder[F]
      .resource
      .map(FollowRedirect(maxRedirects = 5))
      .map(RequestLogger(logHeaders = true, logBody = true))
      .map(ResponseLogger(logHeaders = true, logBody = true))

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

  def makeSpotify[F[_]: UserOutput: Concurrent](spotifyClient: Client[F], sonosClient: Client[F]): F[Spotify[F]] =
    SimpleRestJsonBuilder(SpotifyApiGen).client[F](spotifyClient, com.kubukoz.next.api.spotify.baseUri).liftTo[F].flatMap { spotifyApi =>
      SimpleRestJsonBuilder(SonosApiGen).client[F](sonosClient, sonos.baseUri).liftTo[F].flatMap { sonosApi =>
        given SpotifyApi[F] = spotifyApi
        given SonosApi[F] = sonosApi

        given Spotify.DeviceInfo[F] = Spotify.DeviceInfo.instance(spotifyClient)
        given Spotify.SonosInfo[F] = Spotify.SonosInfo.instance[F]

        SpotifyChoice
          .choose[F]
          .map { choice =>
            given Spotify.Playback[F] = Spotify
              .Playback
              .suspend {
                choice.map(
                  _.fold(
                    sonos = room => Spotify.Playback.sonosInstance[F](room),
                    spotify = Spotify.Playback.spotifyInstance[F]
                  )
                )
              }

            given Spotify.Switch[F] = Spotify.Switch.suspend {
              choice.map(
                _.fold(
                  // flipped order
                  sonos = _ => Spotify.Switch.spotifyInstance[F],
                  spotify = Spotify.Switch.sonosInstance[F](Group("RINCON_7828CA925D6601400:3149079976", "Living Room"))
                )
              )
            }
            Spotify.instance[F](spotifyClient)
          }
      }
    }

}
