package com.kubukoz.next

import com.kubukoz.next.util.Config
import io.circe.syntax._
import io.circe.Printer
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import cats.tagless.finalAlg
import java.nio.file.NoSuchFileException
import com.kubukoz.next.util.ConsoleRead
import com.ocadotechnology.sttp.oauth2.Secret

@finalAlg
trait ConfigLoader[F[_]] {
  def saveConfig(config: Config): F[Unit]
  def loadConfig: F[Config]
}

object ConfigLoader extends LowPriority {

  def cached[F[_]: Sync]: ConfigLoader[F] => F[ConfigLoader[F]] =
    underlying =>
      underlying.loadConfig.flatMap(Ref.of(_)).map { ref =>
        new ConfigLoader[F] {
          def saveConfig(config: Config): F[Unit] = underlying.saveConfig(config) *> ref.set(config)
          val loadConfig: F[Config] = ref.get
        }
      }

  def withCreateFileIfMissing[F[_]: Console: MonadThrow](configPath: Path): ConfigLoader[F] => ConfigLoader[F] = {
    implicit val showPath: Show[Path] = Show.fromToString

    val validInput = "Y"
    val askMessage = show"Didn't find config file at $configPath. Should I create one? ($validInput/n)"

    def askToCreateFile(originalException: NoSuchFileException): F[Config] =
      for {
        _            <- Console[F].putStrLn(askMessage)
        _            <- Console[F].readLn.map(_.trim).ensure(originalException)(_.equalsIgnoreCase(validInput))
        clientId     <- ConsoleRead.readWithPrompt[F, String]("Client ID")
        clientSecret <- ConsoleRead.readWithPrompt[F, Secret[String]]("Client secret")
      } yield Config(clientId, clientSecret, Config.defaultPort, none, none)

    underlying =>
      new ConfigLoader[F] {
        val loadConfig: F[Config] = underlying.loadConfig.recoverWith { case e: NoSuchFileException =>
          askToCreateFile(e).flatTap(saveConfig) <*
            Console[F].putStrLn(s"Saved config to new file at $configPath")
        }

        def saveConfig(config: Config): F[Unit] = underlying.saveConfig(config)
      }
  }

  def default[F[_]: Sync: ContextShift](configPath: Path, blocker: Blocker): ConfigLoader[F] =
    new ConfigLoader[F] {

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
