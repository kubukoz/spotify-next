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
import org.http4s.Response
import org.http4s.Request

object middlewares {
  type BracketThrow[F[_]] = Bracket[F, Throwable]

  def retryUnauthorizedWith[F[_]: Sync: Console](beforeRetry: F[Unit]): Client[F] => Client[F] = {
    def doBeforeRetry(response: Response[F]) = {
      val showBody = response.bodyAsText.compile.string.flatMap(Console[F].putStrLn)

      Resource.liftF(
        Console[F].putStrLn("Received unauthorized response") *>
          showBody *>
          Console[F].putStrLn("Login to retry") *>
          beforeRetry
      )
    }

    client =>
      Client { req =>
        client.run(req).flatMap {
          case response if response.status === Status.Unauthorized => doBeforeRetry(response) *> client.run(req)
          case response                                            => Resource.pure(response)
        }
      }
  }

  def implicitHost[F[_]: BracketThrow](hostname: String): Client[F] => Client[F] = {
    val newAuthority = Some(Uri.Authority(None, RegName(CaseInsensitiveString(hostname))))

    client =>
      Client { req =>
        val newRequest = req.withUri(req.uri.copy(authority = newAuthority))

        client.run(newRequest)
      }
  }

  def withToken[F[_]: Token.Ask: BracketThrow: Console]: Client[F] => Client[F] = {
    val loadToken = Resource.liftF(Token.ask[F]).map(_.value.trim)

    val warnEmptyToken =
      Console[F].putStrLn("Loaded token is empty, any API calls will probably have to be retried...")

    def withToken(token: String): Request[F] => Request[F] =
      _.withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

    client =>
      Client[F] { req =>
        loadToken.flatMap {
          case ""    => Resource.liftF(warnEmptyToken) *> client.run(req)
          case token => client.run(withToken(token)(req))
        }
      }
  }
}
