package com.kubukoz.next

import cats.effect.unsafe.IORuntime

object RuntimePlatform {
  val default: IORuntime = IORuntime.global
}
