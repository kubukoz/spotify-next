package com.kubukoz.next.api

import org.http4s.implicits.*
import org.http4s.Uri

object spotify {
  val baseUri: Uri = uri"https://api.spotify.com"
}
