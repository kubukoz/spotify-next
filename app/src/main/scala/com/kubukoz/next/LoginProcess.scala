package com.kubukoz.next

import cats.implicits.*
import com.kubukoz.next.util.Config
import cats.Monad
import monocle.Lens
import com.kubukoz.next.util.Config.RefreshToken
import com.kubukoz.next.util.Config.Token
import cats.Applicative
import cats.kernel.Monoid

trait LoginProcess[F[_]] {
  def login: F[Unit]
}

object LoginProcess {

  def apply[F[_]](
    using F: LoginProcess[F]
  ): LoginProcess[F] = F

  def instance[F[_]: UserOutput: ConfigLoader: Monad](
    loginAlg: Login[F],
    tokensLens: Lens[
      Config,
      (
        Option[Token],
        Option[RefreshToken]
      )
    ]
  ): LoginProcess[F] = new LoginProcess[F] {

    def login: F[Unit] = for {
      tokens <- loginAlg.server
      config <- ConfigLoader[F].loadConfig
      newConfig = tokensLens.replace((tokens.access.some, tokens.refresh.some))(config)
      _      <- ConfigLoader[F].saveConfig(newConfig)
      _      <- UserOutput[F].print(UserMessage.SavedToken)
    } yield ()

  }

  given [F[_]: Applicative]: Monoid[LoginProcess[F]] with {

    override val empty: LoginProcess[F] = new LoginProcess[F] {
      val login: F[Unit] = Applicative[F].unit
    }

    override def combine(
      x: LoginProcess[F],
      y: LoginProcess[F]
    ): LoginProcess[F] = new LoginProcess[F] {
      val login: F[Unit] = x.login *> y.login
    }

  }

  extension [F[_]: Monad](
    loginProcess: LoginProcess[F]
  ) {

    def orRefresh(
      refresh: RefreshTokenProcess[F]
    ): LoginProcess[F] = new LoginProcess[F] {

      override val login: F[Unit] =
        refresh
          .canRefreshToken
          .ifM(
            ifTrue = refresh.refreshUserToken,
            ifFalse = loginProcess.login
          )

    }

  }

}
