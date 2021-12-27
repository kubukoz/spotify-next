package com.kubukoz.next

import cats.effect.IO
import scalajs.js
import cats.implicits.*

trait EnvPlatform {

  implicit val forIO: Env[IO] =
    new Env[IO] {

      def get(key: String): IO[Option[String]] =
        IO(js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[String]].toMap.get(key))

    }

}
