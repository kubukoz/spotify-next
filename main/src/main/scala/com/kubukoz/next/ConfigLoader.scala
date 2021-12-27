package com.kubukoz.next

import cats.Applicative
import cats.FlatMap
import cats.MonadThrow
import cats.effect.*
import cats.effect.std.Console
import cats.implicits.*
import com.kubukoz.next.util.Config
import com.kubukoz.next.util.ConsoleRead
import fs2.Pipe
import fs2.io.file.Files
import fs2.io.file.Flags
import fs2.io.file.Path
import io.circe.Printer
import io.circe.syntax.*
import ConsolePolyfill.*
import java.nio.file.StandardOpenOption
import fs2.io.file.NoSuchFileException

trait ConfigLoader[F[_]] {
  def saveConfig(config: Config): F[Unit]
  def loadConfig: F[Config]
}

object ConfigLoader {
  def apply[F[_]](using F: ConfigLoader[F]): ConfigLoader[F] = F

  def cached[F[_]: Ref.Make: FlatMap]: ConfigLoader[F] => F[ConfigLoader[F]] =
    underlying =>
      underlying.loadConfig.flatMap(Ref[F].of(_)).map { ref =>
        new ConfigLoader[F] {
          def saveConfig(config: Config): F[Unit] = underlying.saveConfig(config) *> ref.set(config)
          val loadConfig: F[Config] = ref.get
        }
      }

  def withCreateFileIfMissing[F[_]: UserOutput: Console: ConsolePolyfill: MonadThrow](
    configPath: Path
  ): ConfigLoader[F] => ConfigLoader[F] = {

    val validInput = "Y"

    def askToCreateFile(originalException: Throwable): F[Config] =
      for {
        _            <- UserOutput[F].print(UserMessage.ConfigFileNotFound(configPath, validInput))
        _            <- Console[F].readLineCrossCompat.map(_.trim).ensure(originalException)(_.equalsIgnoreCase(validInput))
        clientId     <- ConsoleRead.readWithPrompt[F, String]("Client ID")
        clientSecret <- ConsoleRead.readWithPrompt[F, String]("Client secret")
      } yield Config(clientId, clientSecret, Config.defaultPort, none, none)

    underlying =>
      new ConfigLoader[F] {
        val loadConfig: F[Config] = underlying.loadConfig.recoverWith { case e: NoSuchFileException =>
          askToCreateFile(e).flatTap(saveConfig) <*
            UserOutput[F].print(UserMessage.SavedConfig(configPath))
        }

        def saveConfig(config: Config): F[Unit] = underlying.saveConfig(config)
      }
  }

  def default[F[_]: Files: MonadThrow](configPath: Path)(using fs2.Compiler[F, F]): ConfigLoader[F] =
    new ConfigLoader[F] {

      private val createOrOverwriteFile: Pipe[F, Byte, Nothing] = bytes =>
        fs2.Stream.exec(configPath.parent.traverse_(Files[F].createDirectories)) ++
          bytes.through(
            Files[F].writeAll(configPath, Flags.Write)
          )

      def saveConfig(config: Config): F[Unit] =
        fs2
          .Stream
          .emit(config)
          .map(_.asJson.printWith(Printer.spaces2.copy(colonLeft = "")))
          .through(fs2.text.utf8.encode[F])
          .through(createOrOverwriteFile)
          .compile
          .drain

      val loadConfig: F[Config] =
        Files[F]
          .readAll(configPath)
          .through(fs2.text.utf8.decode[F])
          .compile
          .string
          .flatMap(io.circe.parser.decode[Config](_).liftTo[F])

    }

  extension [F[_]: Applicative](cl: ConfigLoader[F]) def configAsk: Config.Ask[F] = Config.askLiftF(cl.loadConfig)

}
