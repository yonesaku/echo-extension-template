package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
// FIX: Info and Input were referenced incorrectly, fix imports:
import dev.brahmkshatriya.echo.common.models.Shelf.Info
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
// FIX: Setting.Input is now just Input, fix imports:
import dev.brahmkshatriya.echo.common.settings.Setting.Input
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import android.content.Context 
import dev.brahmkshatriya.echo.common.models.Quality // Import for new parameter

/**
 * A data class to represent the expected JSON structure for each track.
 */
@Serializable
private data class DriveTrackInfo(
    val id: String, // Google Drive File ID
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String? = null // Optional URL for album art
)

/**
 * An extension to stream music from public Google Drive links.
 */
open class GoogleDriveExtension : ExtensionClient, HomeFeedClient, TrackClient {

    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        // FIX: Replaced Setting.Input with the imported Input
        Input(
            title = "Tracks JSON",
            key = "tracks_json",
            summary = "A JSON array of tracks with id, title, artist, album, and artUrl.",
            value = tracksJson,
            isTextArea = true
        )
    )

    private val tracksJson get() = setting.getString("tracks_json") ?: "[]"
    private val json = Json { ignoreUnknownKeys = true }

    private fun getTracksFromSettings(): List<DriveTrackInfo> {
        return try {
            json.decodeFromString<List<DriveTrackInfo>>(tracksJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun DriveTrackInfo.toTrack(): Track {
        val artistName = this.artist
        val albumName = this.album
        val albumId = "album:$albumName"
        val artist = Artist(id = "artist:$artistName", name = artistName)
        
        return Track(
            id = "gdrive:$id",
            title = this.title,
            cover = this.artUrl?.toImageHolder(),
            artists = listOf(artist),
            album = Album(
                id = albumId,
                title = albumName,
                artists = listOf(artist),
                cover = this.artUrl?.toImageHolder()
            )
        )
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tracks = getTracksFromSettings().map { it.toTrack() }

        val shelves: List<Shelf> = if (tracks.isEmpty()) {
            listOf(
                // FIX: Unresolved reference 'Info' resolved by import
                Info(
                    id = "no_tracks",
                    title = "No Tracks Found",
                    subtitle = "Please add your tracks in the extension settings."
                )
            )
        } else {
            // Group tracks by album title
            tracks.groupBy { it.album?.title } // FIX: Safe call because 'album' is now nullable
                .mapNotNull { (albumTitle, trackList) ->
                    // FIX: Ensure album is not null before proceeding
                    val album = trackList.first().album ?: return@mapNotNull null
                    
                    // Create a Shelf item for the album
                    Shelf.Lists.Items(
                        id = album.id,
                        title = albumTitle ?: "Unknown Album", // Use a fallback title
                        // FIX: Ensure list is non-null and correctly typed as List<EchoMediaItem>
                        list = listOf(album) as List<EchoMediaItem> 
                    )
                }
        }

        return Feed(tabs = emptyList()) {
            PagedData.Single { shelves }.toFeedData()
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean, context: Context): Track {
        val fileId = track.id.removePrefix("gdrive:")
        val directDownloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        
        val streamable = Streamable(
            id = directDownloadUrl,
            type = Streamable.MediaType.Server,
            extras = emptyMap()
        )

        return track.copy(
            streamables = listOf(streamable)
        )
    }

    /**
     * FIX: Added 'quality: Quality' parameter (No value passed for parameter 'quality').
     */
    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
        context: Context,
        quality: Quality // Added the missing parameter
    ): Streamable.Media {

        if (streamable.type != Streamable.MediaType.Server) {
            throw ClientException.NotSupported("Unsupported streamable type")
        }

        val url = streamable.id

        return Streamable.Source.Http(
            request = url.toGetRequest(),
            decryption = null
        ).toMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }
}
