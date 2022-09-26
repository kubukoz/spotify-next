package com.kubukoz.next.util

import com.kubukoz.next.spotify.UriFormat
import org.http4s.Uri
import smithy4s.Refinement
import smithy4s.RefinementProvider

object UriProvider {
  implicit val provider: RefinementProvider[UriFormat, String, Uri] =
    Refinement.drivenBy[UriFormat](Uri.fromString(_).left.map(_.message), _.renderString)
}
