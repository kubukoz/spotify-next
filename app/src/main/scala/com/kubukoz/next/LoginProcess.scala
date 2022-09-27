package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.util.Config
import cats.Monad
import monocle.Lens
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import cats.Applicative
import cats.Foldable

trait LoginProcess[F[_]] {
  def login: F[Unit]
}

object LoginProcess {
  def apply[F[_]](using F: LoginProcess[F]): LoginProcess[F] = F

  def instance[F[_]: UserOutput: ConfigLoader: Monad](
    loginAlg: Login[F],
    tokensLens: Lens[Config, (Option[Token], Option[RefreshToken])]
  ): LoginProcess[F] = new LoginProcess[F] {

    def login: F[Unit] = for {
      tokens <- loginAlg.server
      config <- ConfigLoader[F].loadConfig
      newConfig = tokensLens.replace((tokens.access.some, tokens.refresh.some))(config)
      _      <- ConfigLoader[F].saveConfig(newConfig)
      _      <- UserOutput[F].print(UserMessage.SavedToken)
    } yield ()

  }

  def combineAll[F[_]: Applicative, G[_]: Foldable](
    loginProcesses: G[LoginProcess[F]]
  ): LoginProcess[F] = new LoginProcess[F] {
    val login: F[Unit] = loginProcesses.traverse_(_.login)
  }

  extension [F[_]: Monad](loginProcess: LoginProcess[F])

    def orRefresh(refresh: RefreshTokenProcess[F]): LoginProcess[F] = new LoginProcess[F] {

      override val login: F[Unit] =
        refresh
          .canRefreshToken
          .ifM(
            ifTrue = refresh.refreshUserToken,
            ifFalse = loginProcess.login
          )

    }

}
