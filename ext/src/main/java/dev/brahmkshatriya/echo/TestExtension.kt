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

class DriveLinkExtension : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private val json = Json { ignoreUnknownKeys = true }

    private var tracksData = mutableListOf<TrackData>()
    private val albumsCache = mutableMapOf<String, AlbumData>()

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

    data class AlbumData(
        val name: String,
        val artist: String,
        val year: String?,
        val genre: String?,
        val artwork: String?,
        val tracks: MutableList<TrackData>
    )

    @Serializable
    data class MusicLibrary(
        val tracks: List<TrackData>
    )

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingTextInput(
                title = "Music JSON",
                key = "music_json",
                summary = "Paste your music library JSON here",
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
                tracksData = library.tracks.toMutableList()
                organizeIntoAlbums()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        if (albumsCache.isEmpty()) {
            organizeIntoAlbums()
        }

        // Use Java Collections.sort instead of Kotlin's sortedBy
        val albumList = albumsCache.values.toList()
        val sortedAlbums = java.util.ArrayList(albumList)
        java.util.Collections.sort(sortedAlbums) { a, b -> a.name.compareTo(b.name) }
        
        val albums = sortedAlbums.map { albumData ->
            Album(
                id = albumData.name,
                title = albumData.name,
                cover = albumData.artwork?.let { url ->
                    NetworkRequestImageHolder(
                        request = NetworkRequest(url = url, headers = emptyMap()),
                        crop = false
                    )
                },
                artists = listOf(
                    Artist(
                        id = albumData.artist,
                        name = albumData.artist
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
        albumData.year?.let { parts.add(it) }
        albumData.genre?.let { parts.add(it) }
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
                        id = trackData.artist,
                        name = trackData.artist
                    )
                ),
                album = Album(
                    id = albumData.name,
                    title = albumData.name,
                    cover = albumData.artwork?.let { url ->
                        NetworkRequestImageHolder(
                            request = NetworkRequest(url = url, headers = emptyMap()),
                            crop = false
                        )
                    }
                ),
                duration = trackData.duration,
                cover = trackData.albumArt?.let { url ->
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
        val directUrl = getDropboxStreamUrl(streamable.id)

        val networkRequest = NetworkRequest(
            url = directUrl,
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

    private fun organizeIntoAlbums() {
        albumsCache.clear()

        tracksData.forEach { trackData ->
            val albumName = trackData.album
            val artistName = trackData.artist

            val albumData = albumsCache.getOrPut(albumName) {
                AlbumData(
                    name = albumName,
                    artist = artistName,
                    year = trackData.year,
                    genre = trackData.genre,
                    artwork = trackData.albumArt,
                    tracks = mutableListOf()
                )
            }
            albumData.tracks.add(trackData)
        }
    }

    private fun getDropboxStreamUrl(shareUrl: String): String {
        // Convert Dropbox share link to direct streaming link
        // Input: https://www.dropbox.com/s/xxxxxxxx/file.mp3?dl=0
        // Output: https://www.dropbox.com/s/xxxxxxxx/file.mp3?raw=1
        return shareUrl.replace("?dl=0", "?raw=1")
            .replace("dl=0", "raw=1")
    }
}

/*
 * DROPBOX STREAMING SETUP:
 * 
 * 1. Upload your music files to Dropbox
 * 2. Share each file and get the link
 * 3. Link will look like: https://www.dropbox.com/s/xxxxxxxx/song.mp3?dl=0
 * 4. Use this EXACT link in your JSON (extension auto-converts to ?raw=1)
 * 
 * DEPENDENCIES in ext/build.gradle.kts:
 * 
 * dependencies {
 *     compileOnly(libs.echo.common)
 *     compileOnly(libs.kotlin.stdlib)
 *     compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
 *     compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
 *     
 *     testImplementation(libs.junit)
 *     testImplementation(libs.coroutines.test)
 *     testImplementation(libs.echo.common)
 * }
 * 
 * EXAMPLE JSON (use full Dropbox share URLs):
 * {
 *   "tracks": [
 *     {
 *       "fileId": "https://www.dropbox.com/s/abc123/song.mp3?dl=0",
 *       "title": "Hey Jude",
 *       "artist": "The Beatles",
 *       "album": "Hey Jude",
 *       "albumArt": "https://www.dropbox.com/s/xyz789/cover.jpg?raw=1",
 *       "year": "1968",
 *       "genre": "Rock",
 *       "duration": 431
 *     }
 *   ]
 * }
 * 
 * KEY FEATURES:
 * ✅ Uses Dropbox direct streaming (?raw=1)
 * ✅ Should avoid persistent caching
 * ✅ 2GB free storage
 * ✅ fileId is the full Dropbox share URL
 * ✅ Extension automatically converts ?dl=0 to ?raw=1
 */