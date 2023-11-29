package com.kubukoz.next.util

import cats.effect.std.Console
import cats.MonadThrow
import cats.implicits.*

trait ConsoleRead[A] {

  def read(
    s: String
  ): Either[Throwable, A]

}

object ConsoleRead {

  def apply[A](
    using A: ConsoleRead[A]
  ): ConsoleRead[A] = A

  given ConsoleRead[String] = new ConsoleRead[String] {

    def read(
      s: String
    ): Either[Throwable, String] = Right(s)

  }

  def readWithPrompt[F[_]: Console: MonadThrow, A: ConsoleRead](
    promptText: String
  ): F[A] =
    Console[F].print(promptText + ": ") *> Console[F]
      .readLine
      .map(Option(_).getOrElse(""))
      .flatMap(ConsoleRead[A].read(_).liftTo[F])

}
