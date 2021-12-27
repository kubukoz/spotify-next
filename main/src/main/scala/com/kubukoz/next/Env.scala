package com.kubukoz.next

import cats.implicits.*

trait Env[F[_]] {
  def get(key: String): F[Option[String]]
}

object Env extends EnvPlatform {
  def apply[F[_]](implicit F: Env[F]): Env[F] = F
}
