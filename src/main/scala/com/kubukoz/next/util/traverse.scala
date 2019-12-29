package com.kubukoz.next.util

import cats.tagless.InvariantK

object traverse {

  // this should be in cats, it's literally code from the Traverse+Reducible instance
  implicit def tuple2NonEmptyTraverse[X]: NonEmptyTraverse[(X, *)] = new NonEmptyTraverse[(X, *)] {

    def nonEmptyTraverse[G[_], A, B](fa: (X, A))(f: A => G[B])(implicit G: Apply[G]): G[(X, B)] =
      G.map(f(fa._2))((fa._1, _))

    def foldLeft[A, B](fa: (X, A), b: B)(f: (B, A) => B): B = f(b, fa._2)

    def foldRight[A, B](fa: (X, A), lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = f(fa._2, lb)

    def reduceLeftTo[A, B](fa: (X, A))(f: A => B)(g: (B, A) => B): B = f(fa._2)

    def reduceRightTo[A, B](fa: (X, A))(f: A => B)(g: (A, Eval[B]) => Eval[B]): Eval[B] =
      Now(f(fa._2))
  }

  // this should be in cats-tagless
  implicit val invariantKNonEmptyTraverse: InvariantK[NonEmptyTraverse] = new InvariantK[NonEmptyTraverse] {

    def imapK[F[_], G[_]](af: NonEmptyTraverse[F])(fk: F ~> G)(gk: G ~> F): NonEmptyTraverse[G] =
      new NonEmptyTraverse[G] {
        def foldLeft[A, B](fa: G[A], b: B)(f: (B, A) => B): B = af.foldLeft(gk(fa), b)(f)
        def foldRight[A, B](fa: G[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = af.foldRight(gk(fa), lb)(f)
        def reduceLeftTo[A, B](fa: G[A])(f: A => B)(g: (B, A) => B): B = af.reduceLeftTo(gk(fa))(f)(g)

        def reduceRightTo[A, B](fa: G[A])(f: A => B)(g: (A, Eval[B]) => Eval[B]): Eval[B] =
          af.reduceRightTo(gk(fa))(f)(g)

        def nonEmptyTraverse[H[_]: Apply, A, B](fa: G[A])(f: A => H[B]): H[G[B]] =
          af.nonEmptyTraverse(gk(fa))(a => f(a)).map(fk(_))
      }
  }

}
