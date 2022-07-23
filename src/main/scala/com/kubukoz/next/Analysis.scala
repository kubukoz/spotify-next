package com.kubukoz.next

import com.kubukoz.next.spotify.AudioAnalysis
import com.kubukoz.next.spotify.SpotifyApi
import com.kubukoz.next.client.spotify.TrackUri
import cats.effect.kernel.Ref
import cats.FlatMap
import cats.implicits.*
import cats.effect.kernel.MonadCancel

trait Analysis[F[_]] {
  def getAnalysis(trackUri: TrackUri): F[AudioAnalysis]
}

object Analysis {

  def apply[F[_]](implicit F: Analysis[F]): Analysis[F] = F

  def instance[F[_]: SpotifyApi]: Analysis[F] = new:
    def getAnalysis(trackUri: TrackUri): F[AudioAnalysis] = SpotifyApi[F].getAudioAnalysis(trackUri.id)

  // "best-effort" cache of the underlying instance. Only has one slot and no concurrency guarantees.
  def cached[F[_]: Ref.Make](underlying: Analysis[F])(using MonadCancel[F, ?]): F[Analysis[F]] = {
    enum State {
      case Initial
      case HasResult(key: TrackUri, result: AudioAnalysis)
    }

    Ref[F].of(State.Initial: State).map { state =>
      new:
        def getAnalysis(trackUri: TrackUri): F[AudioAnalysis] = MonadCancel[F].uncancelable { poll =>
          poll(state.get.flatMap {
            case State.HasResult(`trackUri`, result) => result.pure[F]
            case _                                   => underlying.getAnalysis(trackUri)
          }).flatMap { analysis =>
            state
              .set(State.HasResult(trackUri, analysis))
              .as(analysis)
          }
        }
    }
  }

}
