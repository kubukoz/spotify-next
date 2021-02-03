package com.kubukoz.next.util

import simulacrum.typeclass
import cats.tagless.autoFunctor
import com.ocadotechnology.sttp.oauth2.Secret

@typeclass
@autoFunctor
trait ConsoleRead[A] {
  def read(s: String): Either[Throwable, A]
}

object ConsoleRead {

  implicit val readString: ConsoleRead[String] = new ConsoleRead[String] {
    def read(s: String): Either[Throwable, String] = Right(s)
  }

  implicit def readSecret[A: ConsoleRead]: ConsoleRead[Secret[A]] = ConsoleRead[A].map(Secret(_))

  def readWithPrompt[F[_]: Console: MonadThrow, A: ConsoleRead](promptText: String): F[A] =
    Console[F].putStr(promptText + ": ") *> Console[F]
      .readLn
      .map(Option(_).getOrElse(""))
      .flatMap(ConsoleRead[A].read(_).liftTo[F])

}
