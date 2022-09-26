package com.kubukoz.next

import epollcat.unsafe.EpollRuntime
import cats.effect.unsafe.IORuntime

object RuntimePlatform {
  val default: IORuntime = EpollRuntime.global
}
