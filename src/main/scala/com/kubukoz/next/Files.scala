package com.kubukoz.next

import com.kubukoz.next.util.Config
import io.circe.syntax._
import io.circe.Printer
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import cats.mtl.ApplicativeAsk

trait Files[F[_]] {
  def saveConfig(config: Config): F[Unit]
  def loadConfig: F[Config.Ask[F]]
}

object Files {

  def instance[F[_]: Sync: ContextShift](configPath: Path, blocker: Blocker): Files[F] = new Files[F] {

    def saveConfig(config: Config): F[Unit] =
      fs2
        .Stream
        .emit(config)
        .map(_.asJson.printWith(Printer.spaces2.copy(colonLeft = "")))
        .through(fs2.text.utf8Encode[F])
        .through(
          fs2
            .io
            .file
            .writeAll[F](configPath, blocker, List(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        )
        .compile
        .drain

    def loadConfig: F[Config.Ask[F]] =
      fs2
        .io
        .file
        .readAll[F](configPath, blocker, 4096)
        .through(io.circe.fs2.byteStreamParser[F])
        .through(io.circe.fs2.decoder[F, Config])
        .compile
        .lastOrError
        .map(ApplicativeAsk.const[F, Config](_))

  }
}
