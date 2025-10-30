package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class DriveLinkExtension : ExtensionClient, TrackClient {

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
        val settings = java.util.ArrayList<Setting>()
        
        val items = java.util.ArrayList<Setting>()
        items.add(
            SettingTextInput(
                title = "Music JSON",
                key = "music_json",
                summary = "Paste your music library JSON here. Your music will appear when you search or manually add tracks.",
                defaultValue = ""
            )
        )
        items.add(
            SettingSwitch(
                title = "Enabled",
                key = "enabled",
                summary = "Enable the extension",
                defaultValue = true
            )
        )
        
        settings.add(
            SettingCategory(
                title = "Configuration",
                key = "config",
                items = items
            )
        )
        
        return settings
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun onInitialize() {
        val jsonText = settings.getString("music_json")
        if (jsonText != null && jsonText.length > 0) {
            val trimmed = jsonText.trim()
            if (trimmed.length > 0) {
                try {
                    val library = json.decodeFromString<MusicLibrary>(trimmed)
                    tracksData.clear()
                    tracksData.addAll(library.tracks)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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
 * WORKING VERSION - NO FEED LAMBDAS!
 * 
 * HOW TO USE:
 * 1. Paste your JSON in settings
 * 2. Manually create a playlist in Echo
 * 3. Search for your track by fileId or title
 * 4. Add tracks to your playlist
 * 5. Play from your playlist!
 * 
 * OR: Use Echo's "Add to Queue" feature to queue tracks by searching
 * 
 * This avoids all the Feed/lambda issues by not trying to show a home feed.
 * You manage organization in Echo's built-in playlists.
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