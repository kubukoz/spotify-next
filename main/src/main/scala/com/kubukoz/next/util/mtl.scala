package com.kubukoz.next.util

import monocle.Getter
import cats.Applicative
import cats.implicits.*

trait AskFor[A] {
  type Ask[F[_]] = cats.mtl.Ask[F, A]
  def AskInstance[F[_]](using ask: Ask[F]): Ask[F] = ask
  def ask[F[_]](using ask: Ask[F]): F[A] = ask.ask

  def askBy[F[_]: Applicative, Parent](askParent: cats.mtl.Ask[F, Parent])(getter: Getter[Parent, A]): Ask[F] = askLiftF(
    askParent.reader(getter.get)
  )

  def askLiftF[F[_]: Applicative](fa: F[A]): Ask[F] =
    new cats.mtl.Ask[F, A] {
      val applicative: Applicative[F] = Applicative[F]
      def ask[E2 >: A]: F[E2] = fa.widen
    }

}
