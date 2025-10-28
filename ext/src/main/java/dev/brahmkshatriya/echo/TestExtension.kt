package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.MusicClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class GoogleDriveExtension : ExtensionClient, MusicClient {

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            Setting.Text("metadataURL", "Metadata JSON URL", ""),
            Setting.TextArea("driveLinks", "Google Drive Links (one per line)", "")
        )
    }

    private val httpClient = OkHttpClient()
    private var albums: List<Album> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var trackMap: Map<String, String> = emptyMap()

    override suspend fun onInitialize() {
        val metadataURL = setting.get("metadataURL") ?: return
        val driveLinksRaw = setting.get("driveLinks") ?: ""

        val request = Request.Builder().url(metadataURL).build()
        val response = httpClient.newCall(request).await()
        val json = response.body?.string() ?: return

        val metadata = JSONArray(json)
        val links = driveLinksRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val tempAlbums = mutableListOf<Album>()
        val tempTracks = mutableListOf<Track>()
        val tempMap = mutableMapOf<String, String>()

        for (i in 0 until metadata.length()) {
            val albumObj = metadata.getJSONObject(i)
            val albumId = albumObj.getString("id")
            val albumName = albumObj.getString("name")
            val albumArtist = albumObj.getString("artist")
            val albumCover = albumObj.optString("cover", null)

            val album = Album(albumId, albumName, albumArtist, albumCover)
            tempAlbums.add(album)

            val trackArray = albumObj.getJSONArray("tracks")
            for (j in 0 until trackArray.length()) {
                val trackObj = trackArray.getJSONObject(j)
                val trackId = trackObj.getString("id")
                val trackName = trackObj.getString("name")
                val trackArtist = trackObj.getString("artist")
                val trackDuration = trackObj.optLong("duration", 0L)

                val track = Track(trackId, trackName, trackArtist, album, trackDuration)
                tempTracks.add(track)

                // Match by ID or name (customize as needed)
                val matchedLink = links.find { it.contains(trackId) || it.contains(trackName) }
                if (matchedLink != null) {
                    tempMap[trackId] = matchedLink
                }
            }
        }

        albums = tempAlbums
        tracks = tempTracks
        trackMap = tempMap
    }

    override suspend fun getAlbums(): List<Album> {
        return albums
    }

    override suspend fun getTracks(album: Album): List<Track> {
        return tracks.filter { it.album.id == album.id }
    }

    override suspend fun getStreamables(track: Track): List<Streamable> {
        val url = trackMap[track.id] ?: return emptyList()
        return listOf(Streamable(url))
    }
}