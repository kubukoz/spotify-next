package com.kubukoz.next.util

import org.http4s.client.Client
import org.http4s.util.CaseInsensitiveString
import org.http4s.Uri.RegName
import org.http4s.Uri
import com.kubukoz.next.util.Config.Token
import org.http4s.Credentials
import org.http4s.AuthScheme
import org.http4s.headers.Authorization
import org.http4s.Status

object middlewares {
  type BracketThrow[F[_]] = Bracket[F, Throwable]

  def retryUnauthorizedWith[F[_]: Sync: Console](beforeRetry: F[Unit])(underlying: Client[F]): Client[F] =
    Client { req =>
      underlying.run(req).flatMap {
        case response if response.status === Status.Unauthorized =>
          val showBody = response.bodyAsText.compile.string.flatMap(Console[F].putStrLn)

          Resource.liftF(
            Console[F].putStrLn("Received unauthorized response") *>
              showBody *>
              Console[F].putStrLn("Login to retry") *>
              beforeRetry
          ) *> underlying.run(req)
        case response => Resource.pure(response)
      }
    }

  def implicitHost[F[_]: BracketThrow](hostname: String)(client: Client[F]): Client[F] = Client { req =>
    val newRequest = req.withUri(
      req.uri.copy(authority = Some(Uri.Authority(None, RegName(CaseInsensitiveString(hostname)))))
    )

    client.run(newRequest)
  }

  def withToken[F[_]: Token.Ask: BracketThrow: Console](client: Client[F]): Client[F] =
    Client[F] { req =>
      val warnEmptyToken =
        Console[F].putStrLn("Loaded token is empty, any API calls will probably have to be retried...")

      Resource.liftF(Token.ask[F]).map(_.value.trim).flatMap {
        case "" =>
          Resource.liftF(warnEmptyToken) *> client.run(req)

        case token =>
          val newReq = req.withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
          client.run(newReq)
      }
    }
}
