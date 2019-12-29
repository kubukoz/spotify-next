package com.kubukoz.next

import com.kubukoz.next.util.Config
import io.circe.syntax._
import io.circe.Printer
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import cats.tagless.finalAlg

@finalAlg
trait ConfigLoader[F[_]] {
  def saveConfig(config: Config): F[Unit]
  def loadConfig: F[Config]
}

object ConfigLoader extends LowPriority {

  def cached[F[_]: Sync](underlying: ConfigLoader[F]): F[ConfigLoader[F]] =
    underlying.loadConfig.flatMap(Ref.of(_)).map { ref =>
      new ConfigLoader[F] {
        def saveConfig(config: Config): F[Unit] = underlying.saveConfig(config) *> ref.set(config)
        val loadConfig: F[Config] = ref.get
      }
    }

  def default[F[_]: Sync: ContextShift](configPath: Path, blocker: Blocker): ConfigLoader[F] = new ConfigLoader[F] {

    private val createOrOverwriteFile = fs2
      .io
      .file
      .writeAll[F](configPath, blocker, List(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))

    def saveConfig(config: Config): F[Unit] =
      fs2
        .Stream
        .emit(config)
        .map(_.asJson.printWith(Printer.spaces2.copy(colonLeft = "")))
        .through(fs2.text.utf8Encode[F])
        .through(createOrOverwriteFile)
        .compile
        .drain

    val loadConfig: F[Config] =
      fs2
        .io
        .file
        .readAll[F](configPath, blocker, 4096)
        .through(io.circe.fs2.byteStreamParser[F])
        .through(io.circe.fs2.decoder[F, Config])
        .compile
        .lastOrError
  }

}

trait LowPriority {

  implicit def deriveAskFromLoader[F[_]: ConfigLoader: Applicative]: Config.Ask[F] =
    Config.askLiftF(ConfigLoader[F].loadConfig)

}
