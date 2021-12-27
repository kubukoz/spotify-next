package com.kubukoz.next

import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.std

trait ConsolePolyfill[F[_]] {
  def readLineCrossCompat: F[String]
  def printCrossCompat(s: String): F[Unit]
}

object ConsolePolyfill {

  implicit def forAsync[F[_]: Async]: ConsolePolyfill[F] = new ConsolePolyfill[F] {

    def readLineCrossCompat: F[String] =
      fs2
        .io
        .stdin[F](4096)(Async[F])
        .debug()
        .through(fs2.text.utf8.decode[F])
        .through(fs2.text.lines[F])
        .head
        .compile
        .lastOrError

    def printCrossCompat(s: String): F[Unit] =
      fs2
        .Stream
        .emit(s)
        .through(fs2.text.utf8.encode[F])
        .through(fs2.io.stdout[F])
        .compile
        .drain

  }

  implicit def consoleToPolyfill[F[_]: ConsolePolyfill](c: std.Console[F]): ConsolePolyfill[F] = summon[ConsolePolyfill[F]]

  implicit class IOOps(c: IO.type) {

    def readLineCrossCompat: IO[String] =
      cats.effect.std.Console[IO].readLineCrossCompat

    def printCrossCompat(s: String): IO[Unit] =
      cats.effect.std.Console[IO].printCrossCompat(s)
  }

}
