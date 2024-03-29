package com.kubukoz.next.client

import com.kubukoz.next.Spotify.Error.*
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import monocle.PLens
import org.http4s.Uri
import cats.Functor
import cats.implicits.*
import scala.reflect.TypeTest
import scala.concurrent.duration.*
import com.kubukoz.next.spotify.GetPlayerOutput

object spotify {

  final case class TrackUri(
    id: String
  ) {
    def toFullUri: String = s"spotify:track:$id"
  }

  object TrackUri {

    def decode(
      s: String
    ) = s match {
      case s"spotify:track:$trackId" => TrackUri(trackId).asRight
      case s                         => s"Not a spotify:track:{trackId}: $s".asLeft
    }

  }

  enum Item {

    case Track(
      uri: TrackUri,
      duration: FiniteDuration,
      name: String,
      artists: List[Artist]
    )

  }

  final case class Artist(
    name: String
  )

  object Item {

    def fromApiItem(
      item: com.kubukoz.next.spotify.PlayerItem
    ) = item match {
      case com.kubukoz.next.spotify.PlayerItem.TrackCase(track) =>
        Item.Track(
          TrackUri.decode(track.uri).fold(s => throw new Exception(s), identity),
          track.durationMs.millis,
          track.name,
          artists = track.artists.map(a => Artist(a.name))
        )
    }

  }

  final case class Player[_Ctx, _Item](
    context: _Ctx,
    item: _Item,
    progress: FiniteDuration
  ) {
    private def itemLens[NewItem]: PLens[Player[_Ctx, _Item], Player[_Ctx, NewItem], _Item, NewItem] =
      PLens[Player[_Ctx, _Item], Player[_Ctx, NewItem], _Item, NewItem](_.item)(i => _.copy(item = i))

    private def contextLens[NewContext]: PLens[Player[_Ctx, _Item], Player[NewContext, _Item], _Ctx, NewContext] =
      PLens[Player[_Ctx, _Item], Player[NewContext, _Item], _Ctx, NewContext](_.context)(c => _.copy(context = c))

    // It may not seem like it, but this is traverse.
    def unwrapContext[F[_], Ctx2](
      using ev: _Ctx <:< F[Ctx2],
      fFunctor: Functor[F]
    ): F[Player[Ctx2, _Item]] =
      contextLens[Ctx2]
        .modifyF[F](ev.apply)(this)

    import monocle.syntax.all.*

    def unwrapItem[F[_], Item2](
      using ev: _Item <:< F[Item2],
      fFunctor: Functor[F]
    ): F[Player[_Ctx, Item2]] =
      itemLens[Item2]
        .modifyF[F](ev.apply)(this)

    def narrowContext[DesiredContext <: _Ctx](
      using TypeTest[_Ctx, DesiredContext]
    ): Either[InvalidContext[_Ctx], Player[DesiredContext, _Item]] =
      contextLens[DesiredContext]
        .modifyF {
          case desired: DesiredContext => desired.asRight
          case other                   => InvalidContext(other).asLeft
        }(this)

    def narrowItem[DesiredItem <: _Item](
      using TypeTest[_Item, DesiredItem]
    ): Either[InvalidItem[_Item], Player[_Ctx, DesiredItem]] =
      itemLens[DesiredItem].modifyF {
        case desired: DesiredItem => desired.asRight
        case other                => InvalidItem(other).asLeft
      }(this)

  }

  object Player {

    def fromApiPlayer(
      apiPlayer: GetPlayerOutput
    ): Player[Option[PlayerContext], Option[Item]] =
      Player(
        context = apiPlayer.context.map(PlayerContext.fromApiContext),
        item = apiPlayer.item.map(Item.fromApiItem),
        progress = apiPlayer.progressMillis.millis
      )

  }

  enum PlayerContext {

    case Playlist(
      href: Uri,
      uri: PlaylistUri
    )

    case Album(
      href: Uri
    )

    case Artist(
      href: Uri
    )

    case CollectionCase
  }

  object PlayerContext {

    def fromApiContext(
      ctx: com.kubukoz.next.spotify.PlayerContext
    ): PlayerContext =
      ctx match {
        case com.kubukoz.next.spotify.PlayerContext.PlaylistCase(playlist) =>
          PlayerContext.Playlist(
            Uri.unsafeFromString(playlist.href),
            PlaylistUri.decode(playlist.uri).fold(e => throw new Exception(e), identity)
          )
        case com.kubukoz.next.spotify.PlayerContext.AlbumCase(album)       =>
          PlayerContext.Album(Uri.unsafeFromString(album.href))
        case com.kubukoz.next.spotify.PlayerContext.ArtistCase(artist)     =>
          PlayerContext.Artist(Uri.unsafeFromString(artist.href))
        case com.kubukoz.next.spotify.PlayerContext.CollectionCase         =>
          CollectionCase
      }

  }

  final case class PlaylistUri(
    playlist: String,
    user: Option[String]
  ) {

    def toFullUri: String = user match {
      case Some(user) => s"spotify:user:$user:playlist:$playlist"
      case None       => s"spotify:playlist:$playlist"
    }

  }

  object PlaylistUri {

    def decode(
      s: String
    ): Either[String, PlaylistUri] = s match {
      case s"spotify:user:$userId:playlist:$playlistId" => PlaylistUri(playlistId, userId.some).asRight
      case s"spotify:playlist:$playlistId"              => PlaylistUri(playlistId, none).asRight
      case literallyAnythingElse                        => (literallyAnythingElse + " is not a playlist URI").asLeft
    }

  }

  final case class TokenResponse(
    access_token: String,
    refresh_token: String
  ) derives Codec.AsObject

  final case class RefreshedTokenResponse(
    access_token: String
  ) derives Codec.AsObject

}
