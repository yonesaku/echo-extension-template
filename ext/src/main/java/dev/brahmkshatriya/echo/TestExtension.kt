package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class DriveLinkExtension : ExtensionClient, LibraryFeedClient, TrackClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private val json = Json { ignoreUnknownKeys = true }

    private var tracksData = java.util.ArrayList<TrackData>()

    @Serializable
    data class TrackData(
        val fileId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArt: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val duration: Long? = null
    )

    @Serializable
    data class MusicLibrary(
        val tracks: List<TrackData>
    )

    override suspend fun getSettingItems(): List<Setting> {
        return java.util.Arrays.asList(
            SettingCategory(
                title = "Configuration",
                key = "config",
                items = java.util.Arrays.asList(
                    SettingTextInput(
                        title = "Music JSON",
                        key = "music_json",
                        summary = "Paste your music library JSON here",
                        defaultValue = ""
                    ),
                    SettingSwitch(
                        title = "Enabled",
                        key = "enabled",
                        summary = "Enable the extension",
                        defaultValue = true
                    )
                )
            )
        )
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun onInitialize() {
        val jsonText = settings.getString("music_json")
        if (jsonText != null && jsonText.length > 0) {
            try {
                val library = json.decodeFromString<MusicLibrary>(jsonText)
                tracksData.clear()
                tracksData.addAll(library.tracks)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Show all tracks in Library tab instead of Home
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val tracks = java.util.ArrayList<Track>()
        
        for (trackData in tracksData) {
            val trackCover = if (trackData.albumArt != null) {
                NetworkRequestImageHolder(
                    request = NetworkRequest(url = trackData.albumArt, headers = java.util.HashMap()),
                    crop = false
                )
            } else null

            tracks.add(
                Track(
                    id = trackData.fileId,
                    title = trackData.title,
                    artists = java.util.Collections.singletonList(
                        Artist(
                            id = trackData.artist,
                            name = trackData.artist
                        )
                    ),
                    album = if (trackData.album != null) {
                        Album(
                            id = trackData.album,
                            title = trackData.album
                        )
                    } else null,
                    duration = trackData.duration,
                    cover = trackCover
                )
            )
        }

        val shelf = Shelf.Lists.Tracks(
            id = "all_tracks",
            title = "All Songs",
            list = tracks
        )

        val shelves: List<Shelf> = java.util.Collections.singletonList(shelf as Shelf)

        return Feed(java.util.Collections.emptyList()) {
            PagedData.Single { shelves }.toFeedData()
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val directUrl = getDriveDirectUrl(track.id)

        return Track(
            id = track.id,
            title = track.title,
            artists = track.artists,
            album = track.album,
            duration = track.duration,
            cover = track.cover,
            streamables = java.util.Collections.singletonList(
                Streamable.server(
                    id = track.id,
                    quality = 320
                )
            )
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val directUrl = getDriveDirectUrl(streamable.id)

        val networkRequest = NetworkRequest(
            url = directUrl,
            headers = java.util.HashMap()
        )

        return Streamable.Media.Server(
            sources = java.util.Collections.singletonList(
                Streamable.Source.Http(
                    request = networkRequest,
                    type = Streamable.SourceType.Progressive
                )
            ),
            merged = false
        )
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    private fun getDriveDirectUrl(fileId: String): String {
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }
}

/*
 * SIMPLIFIED VERSION - NO ALBUMS!
 * ✅ Shows all tracks in Library tab
 * ✅ No album organization complexity
 * ✅ Streams from Google Drive
 * ✅ Full metadata support
 * 
 * EXAMPLE JSON:
 * {
 *   "tracks": [
 *     {
 *       "fileId": "1ABC123",
 *       "title": "Hey Jude",
 *       "artist": "The Beatles",
 *       "album": "Hey Jude",
 *       "albumArt": "https://i.imgur.com/art.jpg",
 *       "year": "1968",
 *       "duration": 431
 *     }
 *   ]
 * }
 */