package com.kubukoz.next.util

import java.util.concurrent.TimeUnit

object debug {

  def timedAlloc[F[_]: Timer: Console: Monad, A](name: String)(resource: Resource[F, A]): Resource[F, A] = {

    val now = Timer[F].clock.realTime(TimeUnit.MILLISECONDS)

    Resource.liftF(now).flatMap { before =>
      resource.evalTap { _ =>
        now
          .map(_ - before)
          .map("Allocated " + name + " in " + _ + "ms")
          .flatMap(Console[F].putStrLn(_))
      }
    }
  }

}
