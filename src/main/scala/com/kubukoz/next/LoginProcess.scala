package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.util.Config
import cats.Monad

trait LoginProcess[F[_]] {
  def login: F[Unit]
  def refreshUserToken(refreshToken: Config.RefreshToken): F[Unit]
}

object LoginProcess {
  def apply[F[_]](using F: LoginProcess[F]): LoginProcess[F] = F

  def instance[F[_]: UserOutput: Login: ConfigLoader: Monad]: LoginProcess[F] = new LoginProcess[F] {

    def login: F[Unit] = for {
      tokens <- Login[F].server
      config <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = tokens.access.some, refreshToken = tokens.refresh.some)
      _      <- ConfigLoader[F].saveConfig(newConfig)
      _      <- UserOutput[F].print(UserMessage.SavedToken)
    } yield ()

    def refreshUserToken(refreshToken: Config.RefreshToken): F[Unit] = for {
      newToken <- Login[F].refreshToken(refreshToken)
      config   <- ConfigLoader[F].loadConfig
      newConfig = config.copy(token = newToken.some)
      _        <- ConfigLoader[F].saveConfig(newConfig)
      _        <- UserOutput[F].print(UserMessage.RefreshedToken)
    } yield ()

  }

}
