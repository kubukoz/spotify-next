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
import java.nio.file.Path
import cats.data.OptionT
import cats.MonadThrow

object Program {

  trait System[F[_]] {
    def getenv(name: String): F[Option[String]]
  }

  object System {
    def apply[F[_]](using F: System[F]): System[F] = F

    given [F[_]: Sync]: System[F] = name => Sync[F].delay(java.lang.System.getenv(name)).map(Option(_))
  }

  private def configPath[F[_]: System: MonadThrow]: F[Path] =
    OptionT(System[F].getenv("XDG_CONFIG_HOME"))
      .map(Paths.get(_))
      .getOrElseF(
        System[F]
          .getenv("HOME")
          .flatMap(_.liftTo[F](new Throwable("HOME not defined, I don't even")))
          .map(Paths.get(_))
          .map(_.resolve(".config"))
      )
      .map(
        _.resolve("spotify-next")
          .resolve("config.json")
      )

  def makeLoader[F[_]: Files: System: Ref.Make: UserOutput: Console: fs2.Compiler.Target]: F[ConfigLoader[F]] =
    configPath[F].flatMap { p =>
      ConfigLoader
        .cached[F]
        .compose(ConfigLoader.withCreateFileIfMissing[F](p))
        .apply(ConfigLoader.default[F](p))
    }

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

  def makeSpotify[F[_]: UserOutput: Concurrent](client: Client[F]): F[Spotify[F]] = {
    given Client[F] = client
    given Spotify.DeviceInfo[F] = Spotify.DeviceInfo.instance
    given Spotify.SonosInfo[F] = Spotify.SonosInfo.instance(sonos.baseUri, client)

    SpotifyChoice
      .choose[F]
      .map(
        _.map(
          _.fold(
            room => Spotify.Playback.sonosInstance[F](sonos.baseUri, room, client),
            Spotify.Playback.spotifyInstance[F](client)
          )
        )
      )
      .map(Spotify.Playback.suspend(_))
      .map { playback =>
        given Spotify.Playback[F] = playback

        given Client[F] = client

        Spotify.instance[F]
      }
  }

}
