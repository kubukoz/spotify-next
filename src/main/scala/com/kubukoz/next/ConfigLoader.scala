package com.kubukoz.next

import com.kubukoz.next.util.Config
import io.circe.syntax.*
import io.circe.Printer
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import com.kubukoz.next.util.ConsoleRead
import cats.effect.*
import cats.implicits.*
import cats.Applicative
import cats.effect.std.Console
import fs2.io.file.Files
import cats.FlatMap
import cats.MonadThrow

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

  def withCreateFileIfMissing[F[_]: UserOutput: Console: MonadThrow](configPath: Path): ConfigLoader[F] => ConfigLoader[F] = {

    val validInput = "Y"

    def askToCreateFile(originalException: NoSuchFileException): F[Config] =
      for {
        _            <- UserOutput[F].print(UserMessage.ConfigFileNotFound(configPath, validInput))
        _            <- Console[F].readLine.map(_.trim).ensure(originalException)(_.equalsIgnoreCase(validInput))
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

      private val createOrOverwriteFile =
        Files[F]
          .writeAll(configPath, List(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))

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
        Files[F]
          .readAll(configPath, 4096)
          .through(fs2.text.utf8Decode[F])
          .compile
          .string
          .flatMap(io.circe.parser.decode[Config](_).liftTo[F])

    }

  extension [F[_]: Applicative](cl: ConfigLoader[F]) def configAsk: Config.Ask[F] = Config.askLiftF(cl.loadConfig)

}
