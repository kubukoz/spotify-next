package com.kubukoz.next.util

import cats.effect.std.Console
import cats.MonadThrow
import cats.implicits._

trait ConsoleRead[A] {
  def read(s: String): Either[Throwable, A]
}

object ConsoleRead {
  def apply[A](implicit A: ConsoleRead[A]): ConsoleRead[A] = A

  implicit val readString: ConsoleRead[String] = new ConsoleRead[String] {
    def read(s: String): Either[Throwable, String] = Right(s)
  }

  def readWithPrompt[F[_]: Console: MonadThrow, A: ConsoleRead](promptText: String): F[A] =
    Console[F].print(promptText + ": ") *> Console[F]
      .readLine
      .map(Option(_).getOrElse(""))
      .flatMap(ConsoleRead[A].read(_).liftTo[F])

}
