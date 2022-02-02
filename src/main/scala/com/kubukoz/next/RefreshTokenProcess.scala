package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.util.Config
import monocle.Lens
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import cats.MonadThrow

trait RefreshTokenProcess[F[_]] {
  def canRefreshToken: F[Boolean]
  def refreshUserToken: F[Unit]
}

object RefreshTokenProcess {
  def apply[F[_]](using F: RefreshTokenProcess[F]): RefreshTokenProcess[F] = F

  def instance[F[_]: UserOutput: ConfigLoader: MonadThrow](
    kind: String,
    loginAlg: Login[F],
    tokensLens: Lens[Config, (Option[Token], Option[RefreshToken])]
  ): RefreshTokenProcess[F] = new RefreshTokenProcess[F] {

    val refreshTokenGetter = tokensLens.asGetter.map(_._2)

    val canRefreshToken: F[Boolean] =
      ConfigLoader[F].loadConfig.map { config =>
        refreshTokenGetter.get(config).isDefined
      }

    val refreshUserToken: F[Unit] = for {
      config       <- ConfigLoader[F].loadConfig
      refreshToken <- refreshTokenGetter.get(config).liftTo[F](new Throwable("Refresh token not found! Can't refresh token."))
      newToken     <- loginAlg.refreshToken(refreshToken)
      newConfig = tokensLens.replace((newToken.some, refreshToken.some))(config)
      _            <- ConfigLoader[F].saveConfig(newConfig)
      _            <- UserOutput[F].print(UserMessage.RefreshedToken(kind: String))
    } yield ()

  }

}
