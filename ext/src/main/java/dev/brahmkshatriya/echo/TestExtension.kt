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
import dev.brahmkshatriya.echo.common.models.Shelf.Info
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * A data class to represent the expected JSON structure for each track.
 * The user will need to provide a JSON array of these objects in settings.
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

    /**
     * Provides a text area in the extension settings for the user
     * to paste their JSON data.
     */
    override suspend fun getSettingItems(): List<Setting> = listOf(
        Setting.Input(
            title = "Tracks JSON",
            key = "tracks_json",
            summary = "A JSON array of tracks with id, title, artist, album, and artUrl.",
            value = tracksJson,
            isTextArea = true // Make it a multi-line text box
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
        val albumId = "album:$albumName" // Create consistent ID for the album
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

    /**
     * 핵심 변경 사항: 트랙을 앨범별로 그룹화하고, 앨범을 나타내는 Shelf.Lists.Items로 변환합니다.
     * CORE CHANGE: Groups tracks by album and converts each group into a Shelf.Lists.Items
     * which contains the tracks as media items.
     */
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tracks = getTracksFromSettings().map { it.toTrack() }

        val shelves: List<Shelf> = if (tracks.isEmpty()) {
            listOf(
                Info(
                    id = "no_tracks",
                    title = "No Tracks Found",
                    subtitle = "Please add your tracks in the extension settings."
                )
            )
        } else {
            // 1. Group the tracks by their album title
            tracks.groupBy { it.album.title }
                // 2. Map the resulting groups into Shelf objects
                .map { (albumTitle, trackList) ->
                    // Get the Album object from the first track in the group
                    val album = trackList.first().album
                    
                    // Create a Shelf item (which contains all the tracks in that album)
                    Shelf.Lists.Items(
                        id = album.id, // Use the album's ID for the shelf ID
                        title = albumTitle,
                        // Convert the list of Track objects to MediaItem objects (which Album is a subtype of)
                        list = listOf(album) 
                    )
                }
        }

        return Feed(tabs = emptyList()) {
            PagedData.Single { shelves }.toFeedData()
        }
    }
    
    // --- (The rest of the functions remain the same as the previous correct version) ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
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

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
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
