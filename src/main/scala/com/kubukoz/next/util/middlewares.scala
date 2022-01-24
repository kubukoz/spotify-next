package com.kubukoz.next.util

import cats.effect.MonadCancelThrow
import cats.effect.Resource
import cats.effect.std.Console
import cats.implicits.*
import cats.effect.implicits.*
import com.kubukoz.next.util.Config.Token
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.headers.Authorization
import org.http4s.EntityEncoder
import org.http4s.Uri.Path
import java.nio.charset.StandardCharsets
import org.http4s.Uri.Path.Segment

object middlewares {

  def retryUnauthorizedWith[F[_]: Console: MonadCancelThrow](
    beforeRetry: F[Unit]
  )(
    using fs2.Compiler[F, F]
  ): Client[F] => Client[F] = {
    def doBeforeRetry(response: Response[F]) = {
      val showBody = response.bodyText.compile.string.flatMap(Console[F].println)

      Resource.eval(
        Console[F].println("Received unauthorized response") *>
          showBody *>
          beforeRetry
      )
    }

    client =>
      Client { req =>
        client.run(req).flatMap {
          case response if response.status === Status.Unauthorized => doBeforeRetry(response) *> client.run(req)
          case response                                            => Resource.pure[F, Response[F]](response)
        }
      }
  }

  def logFailedResponse[F[_]: Console: MonadCancelThrow](using fs2.Compiler[F, F]): Client[F] => Client[F] = { client =>
    Client[F] { req =>
      client.run(req).evalMap {
        case response if response.status.isSuccess => response.pure[F]
        case response                              =>
          response
            .bodyText
            .compile
            .string
            .flatTap(text => Console[F].errorln(s"Request $req failed, response: " + text))
            .map(response.withEntity(_))
      }
    }
  }

  def withToken[F[_]: MonadCancelThrow: Console](getToken: F[Option[Token]]): Client[F] => Client[F] = {

    val warnEmptyToken =
      Console[F].println("Loaded token is empty, any API calls will probably have to be retried...")

    def withToken(token: String): Request[F] => Request[F] =
      _.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    client =>
      Client[F] { req =>
        getToken.toResource.flatMap {
          // `eval` is type annotated as a workaround for https://github.com/lampepfl/dotty/issues/14333
          case None        => Resource.eval[F, Unit](warnEmptyToken) *> client.run(req)
          case Some(token) => client.run(withToken(token.value.trim)(req))
        }
      }
  }

  def defaultContentType[F[_]: MonadCancelThrow](tpe: `Content-Type`): Client[F] => Client[F] = client =>
    Client[F] { req =>
      client
        .run(
          req.transformHeaders { headers =>
            headers.get[`Content-Type`].fold(headers.put(tpe))(_ => headers)
          }
        )
    }

  // https://github.com/disneystreaming/smithy4s/issues/72
  def fixupColons[F[_]: MonadCancelThrow]: Client[F] => Client[F] = client =>
    Client { req =>

      def fixupPath(path: Path): Path = Path(
        segments = path.segments.map { seg =>
          val decoded = seg.decoded(StandardCharsets.UTF_8, true, _ => false)

          if (decoded.contains("%3A"))
            Segment(decoded.replace("%3A", ":"))
          else seg
        },
        absolute = path.absolute,
        endsWithSlash = path.endsWithSlash
      )

      client.run(
        req.withPathInfo(fixupPath(req.pathInfo))
      )
    }

}
