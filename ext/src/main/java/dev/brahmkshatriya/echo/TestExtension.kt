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
 *
 * This extension requires the user to provide a JSON blob in settings
 * containing the metadata for each track.
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

    // Safely get the JSON string from settings, defaulting to an empty array.
    private val tracksJson get() = setting.getString("tracks_json") ?: "[]"

    // JSON parser configured to ignore any unknown fields.
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses the JSON string from settings into a list of [DriveTrackInfo] objects.
     */
    private fun getTracksFromSettings(): List<DriveTrackInfo> {
        return try {
            json.decodeFromString<List<DriveTrackInfo>>(tracksJson)
        } catch (e: Exception) {
            // If JSON is malformed, return an empty list and log the error
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Helper function to convert our [DriveTrackInfo] data class
     * into an [Track] model that Echo understands.
     */
    private fun DriveTrackInfo.toTrack(): Track {
        val artistName = this.artist
        return Track(
            id = "gdrive:$id", // Prefix the ID to avoid collisions
            title = this.title,
            cover = this.artUrl?.toImageHolder(),
            artists = listOf(Artist(id = "artist:$artistName", name = artistName)),
            album = Album(
                id = "album:${this.album}",
                title = this.album,
                // This was the fix for: Argument type mismatch
                artists = listOf(Artist(id = "artist:$artistName", name = artistName)),
                cover = this.artUrl?.toImageHolder()
            )
        )
    }

    /**
     * Loads the main feed for this extension.
     * It will display a single shelf containing all the tracks
     * from the user's JSON setting.
     */
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tracks = getTracksFromSettings().map { it.toTrack() }

        // This was the fix for: Return type mismatch
        val shelves: List<Shelf> = if (tracks.isEmpty()) {
            listOf(
                // This was the fix for: Unresolved reference 'Message'
                Info(
                    id = "no_tracks",
                    title = "No Tracks Found",
                    subtitle = "Please add your tracks in the extension settings."
                )
            )
        } else {
            listOf(
                Shelf.Lists.Tracks(
                    id = "my_gdrive_tracks",
                    title = "My Google Drive Tracks",
                    list = tracks
                )
            )
        }

        // Return a Feed with no tabs, just the main content.
        return Feed(tabs = emptyList()) {
            PagedData.Single { shelves }.toFeedData()
        }
    }

    /**
     * Called when the app needs to load streamable information for a track.
     * We attach the [Streamable] object here.
     */
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        // Get the Google Drive File ID from our custom track ID
        val fileId = track.id.removePrefix("gdrive:")

        // Construct the direct download URL for Google Drive
        val directDownloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"

        // This was the fix for: Unresolved reference 'Server'
        val streamable = Streamable(
            id = directDownloadUrl,
            type = Streamable.MediaType.Server, // 'mediaType' was renamed to 'type'
            extras = emptyMap()
        )

        // Return the track with the streamable information attached
        return track.copy(
            streamables = listOf(streamable)
        )
    }

    /**
     * Called when the app is ready to play the [Streamable].
     * This function provides the actual media stream.
     */
    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {

        if (streamable.type != Streamable.MediaType.Server) {
            throw ClientException.NotSupported("Unsupported streamable type")
        }

        // The streamable.id contains the directDownloadUrl we created in loadTrack
        val url = streamable.id

        // Return a simple HTTP source. Echo will handle the streaming.
        // No decryption is needed for a public Google Drive link.
        return Streamable.Source.Http(
            request = url.toGetRequest(),
            decryption = null
        ).toMedia()
    }

    /**
     * This was the fix for: Class 'GoogleDriveExtension' is not abstract
     * This function is required by TrackClient to load related content.
     * We have no related content, so we just return null.
     */
    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }
}
