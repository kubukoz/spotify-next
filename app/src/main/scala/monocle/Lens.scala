package monocle

import cats.Functor

type Lens[S, A] = PLens[S, S, A, A]

object Lens {

  def apply[S, A](
    _get: S => A
  )(
    _set: A => S => S
  ): Lens[S, A] = new Lens[S, A] {

    def get(
      s: S
    ): A = _get(s)

    def set(
      s: S,
      a: A
    ): S = _set(a)(s)

  }

}

trait PLens[S, T, A, B] {

  def get(
    s: S
  ): A

  def set(
    s: S,
    b: B
  ): T

  def replace(
    b: B
  ): S => T = set(_, b)

  def asGetter: Getter[S, A] = get(_)

  def modifyF[F[_]: Functor](
    f: A => F[B]
  )(
    s: S
  ): F[T] = Functor[F].map(f(get(s)))(set(s, _))

}

object PLens {

  def apply[S, T, A, B](
    _get: S => A
  )(
    _set: B => S => T
  ): PLens[S, T, A, B] = new PLens[S, T, A, B] {

    def get(
      s: S
    ): A = _get(s)

    def set(
      s: S,
      b: B
    ): T = _set(b)(s)

  }

}

trait Getter[S, A] {

  def get(
    s: S
  ): A

  def map[B](
    f: A => B
  ): Getter[S, B] = s => f(get(s))

}

object syntax {
  object all {}
}
