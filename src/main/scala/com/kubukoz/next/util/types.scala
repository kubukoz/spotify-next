package com.kubukoz.next.util

object types {
  type MonadThrow[F[_]] = MonadError[F, Throwable]
}
