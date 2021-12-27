package com.kubukoz.next.util

import cats.effect.std.Console
import cats.MonadThrow
import cats.implicits.*
import com.kubukoz.next.ConsolePolyfill.*
import com.kubukoz.next.ConsolePolyfill

trait ConsoleRead[A] {
  def read(s: String): Either[Throwable, A]
}

object ConsoleRead {
  def apply[A](using A: ConsoleRead[A]): ConsoleRead[A] = A

  given ConsoleRead[String] = new ConsoleRead[String] {
    def read(s: String): Either[Throwable, String] = Right(s)
  }

  def readWithPrompt[F[_]: Console: ConsolePolyfill: MonadThrow, A: ConsoleRead](promptText: String): F[A] =
    Console[F].print(promptText + ": ") *> Console[F]
      .readLineCrossCompat
      .map(Option(_).getOrElse(""))
      .flatMap(ConsoleRead[A].read(_).liftTo[F])

}
