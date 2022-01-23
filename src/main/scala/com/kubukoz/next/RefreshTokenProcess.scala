package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.util.Config
import cats.Monad
import monocle.Lens
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token

trait RefreshTokenProcess[F[_]] {
  def refreshUserToken(refreshToken: Config.RefreshToken): F[Unit]
}

object RefreshTokenProcess {
  def apply[F[_]](using F: RefreshTokenProcess[F]): RefreshTokenProcess[F] = F

  def instance[F[_]: UserOutput: ConfigLoader: Monad](
    loginAlg: Login[F],
    tokensLens: Lens[Config, (Option[Token], Option[RefreshToken])]
  ): RefreshTokenProcess[F] = new RefreshTokenProcess[F] {

    def refreshUserToken(refreshToken: Config.RefreshToken): F[Unit] = for {
      newToken <- loginAlg.refreshToken(refreshToken)
      config   <- ConfigLoader[F].loadConfig
      newConfig = tokensLens.replace((newToken.some, refreshToken.some))(config)
      _        <- ConfigLoader[F].saveConfig(newConfig)
      _        <- UserOutput[F].print(UserMessage.RefreshedToken)
    } yield ()

  }

}
