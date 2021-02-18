package com.kubukoz.next.util

import types._

trait ConsoleRead[A] {
  def read(s: String): Either[Throwable, A]
}

object ConsoleRead {
  def apply[A](implicit A: ConsoleRead[A]): ConsoleRead[A] = A

  implicit val readString: ConsoleRead[String] = new ConsoleRead[String] {
    def read(s: String): Either[Throwable, String] = Right(s)
  }

  def readWithPrompt[F[_]: Console: MonadThrow, A: ConsoleRead](promptText: String): F[A] =
    Console[F].putStr(promptText + ": ") *> Console[F]
      .readLn
      .map(Option(_).getOrElse(""))
      .flatMap(ConsoleRead[A].read(_).liftTo[F])

}
