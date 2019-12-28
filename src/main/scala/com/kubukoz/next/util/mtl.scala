package com.kubukoz.next.util

import cats.mtl.ApplicativeAsk

trait AskFor[A] {
  type Ask[F[_]] = ApplicativeAsk[F, A]
  def ask[F[_]](implicit ask: Ask[F]): F[A] = ask.ask
}
