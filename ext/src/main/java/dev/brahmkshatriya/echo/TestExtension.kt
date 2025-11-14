package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class DriveLinkExtension : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private val json = Json { ignoreUnknownKeys = true }

    private val albumsCache = mutableMapOf<String, AlbumData>()
    
    // ** REPLACE WITH YOUR GOOGLE API KEY **
    private val GOOGLE_API_KEY = "AIzaSyDlkrWAvE3h5I5ZxdkmJSM4zZ5R9brY7Y4"
    private val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"

    @Serializable
    data class AlbumConfig(
        val folderId: String,
        val artist: String,
        val album: String,
        val albumArt: String? = null,
        val year: String? = null,
        val genre: String? = null
    )

    @Serializable
    data class MusicLibrary(
        val albums: List<AlbumConfig>
    )

    data class TrackData(
        val fileId: String,
        val title: String,
        val trackNumber: Int?,
        val duration: Long? = null
    )

    data class AlbumData(
        val config: AlbumConfig,
        val tracks: MutableList<TrackData>
    )

    @Serializable
    data class DriveFileList(
        val files: List<DriveFile>
    )

    @Serializable
    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String
    )

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingTextInput(
                title = "Music Library JSON",
                key = "music_json",
                summary = "Paste your albums JSON here",
                defaultValue = ""
            )
        )
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun onInitialize() {
        val jsonText = settings.getString("music_json")
        if (!jsonText.isNullOrBlank()) {
            try {
                val library = json.decodeFromString<MusicLibrary>(jsonText)
                
                // For each album, fetch files from Drive
                library.albums.forEach { albumConfig ->
                    val files = listFilesInFolder(albumConfig.folderId)
                    val tracks = files
                        .filter { it.mimeType.contains("audio") || it.name.endsWith(".mp3") }
                        .mapNotNull { parseTrackFromFilename(it) }
                        .sortedBy { it.trackNumber ?: 999 }
                    
                    if (tracks.isNotEmpty()) {
                        albumsCache[albumConfig.album] = AlbumData(
                            config = albumConfig,
                            tracks = tracks.toMutableList()
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun listFilesInFolder(folderId: String): List<DriveFile> {
        val url = "$DRIVE_API_BASE/files?" +
                "q='$folderId'+in+parents&" +
                "fields=files(id,name,mimeType)&" +
                "key=$GOOGLE_API_KEY"
        
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        
        return json.decodeFromString<DriveFileList>(body).files
    }

    private fun parseTrackFromFilename(file: DriveFile): TrackData? {
        val name = file.name.removeSuffix(".mp3").removeSuffix(".m4a")
        
        // Try to parse "01 - Track Title" format
        val regex = Regex("^(\\d+)\\s*[-.]\\s*(.+)$")
        val match = regex.find(name)
        
        return if (match != null) {
            val trackNum = match.groupValues[1].toIntOrNull()
            val title = match.groupValues[2].trim()
            TrackData(file.id, title, trackNum)
        } else {
            // No track number, use filename as title
            TrackData(file.id, name, null)
        }
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val albums = albumsCache.values.sortedBy { it.config.album }.map { albumData ->
            Album(
                id = albumData.config.album,
                title = albumData.config.album,
                cover = albumData.config.albumArt?.let { url ->
                    NetworkRequestImageHolder(
                        request = NetworkRequest(url = url, headers = emptyMap()),
                        crop = false
                    )
                },
                artists = listOf(
                    Artist(
                        id = albumData.config.artist,
                        name = albumData.config.artist
                    )
                ),
                subtitle = buildAlbumSubtitle(albumData)
            )
        }

        val shelf: Shelf = Shelf.Lists.Items(
            id = "albums",
            title = "Albums",
            list = albums
        )

        return Feed(emptyList()) {
            PagedData.Single<Shelf> { listOf(shelf) }.toFeedData()
        }
    }

    private fun buildAlbumSubtitle(albumData: AlbumData): String {
        val parts = mutableListOf<String>()
        albumData.config.year?.let { parts.add(it) }
        albumData.config.genre?.let { parts.add(it) }
        parts.add("${albumData.tracks.size} tracks")
        return parts.joinToString(" • ")
    }

    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val albumData = albumsCache[album.id] ?: return null

        val tracks = albumData.tracks.map { trackData ->
            Track(
                id = trackData.fileId,
                title = trackData.title,
                artists = listOf(
                    Artist(
                        id = albumData.config.artist,
                        name = albumData.config.artist
                    )
                ),
                album = Album(
                    id = albumData.config.album,
                    title = albumData.config.album,
                    cover = albumData.config.albumArt?.let { url ->
                        NetworkRequestImageHolder(
                            request = NetworkRequest(url = url, headers = emptyMap()),
                            crop = false
                        )
                    }
                ),
                duration = trackData.duration,
                cover = albumData.config.albumArt?.let { url ->
                    NetworkRequestImageHolder(
                        request = NetworkRequest(url = url, headers = emptyMap()),
                        crop = false
                    )
                }
            )
        }

        return Feed(emptyList()) {
            PagedData.Single { tracks }.toFeedData()
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return Track(
            id = track.id,
            title = track.title,
            artists = track.artists,
            album = track.album,
            duration = track.duration,
            cover = track.cover,
            streamables = listOf(
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
        val streamUrl = getDriveApiStreamUrl(streamable.id)

        val networkRequest = NetworkRequest(
            url = streamUrl,
            headers = emptyMap()
        )

        return Streamable.Media.Server(
            sources = listOf(
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

    private fun getDriveApiStreamUrl(fileId: String): String {
        return "$DRIVE_API_BASE/files/$fileId?alt=media&key=$GOOGLE_API_KEY"
    }
}

/*
 * GOOGLE DRIVE API SETUP:
 * 
 * 1. Go to https://console.cloud.google.com/
 * 2. Create project & enable Google Drive API
 * 3. Create API Key (Credentials → API Key)
 * 4. Replace GOOGLE_API_KEY above with your key
 * 5. Organize music in Drive folders (one folder per album)
 * 6. Name files: "01 - Track Title.mp3" or "Track Title.mp3"
 * 
 * DEPENDENCIES (already have these):
 * compileOnly(libs.echo.common)
 * compileOnly(libs.kotlin.stdlib)
 * compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
 * compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
 * 
 * EXAMPLE JSON (MUCH SIMPLER!):
 * {
 *   "albums": [
 *     {
 *       "folderId": "1ABC123XYZ",
 *       "artist": "The Beatles",
 *       "album": "Abbey Road",
 *       "albumArt": "https://drive.google.com/uc?export=view&id=1IMG123",
 *       "year": "1969",
 *       "genre": "Rock"
 *     }
 *   ]
 * }
 * 
 * FEATURES:
 * ✅ Auto-lists files from Drive folders
 * ✅ Parses track numbers from filenames
 * ✅ Uses Drive API for streaming with range requests
 * ✅ One JSON entry per album (not per track!)
 * ✅ Automatically organizes by track number
 */