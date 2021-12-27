package com.kubukoz.next

import cats.effect.IO
import java.lang

trait EnvPlatform {

  implicit val forIO: Env[IO] =
    new Env[IO] {
      def get(key: String): IO[Option[String]] = IO(Option(lang.System.getenv(key)))
    }

}
