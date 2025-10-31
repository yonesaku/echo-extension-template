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
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class DriveLinkExtension : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private val json = Json { ignoreUnknownKeys = true }

    private var tracksData = mutableListOf<TrackData>()
    private val albumsCache = mutableMapOf<String, AlbumData>()

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

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingCategory(
                title = "Configuration",
                key = "config_category",
                items = listOf(
                    SettingTextInput(
                        title = "Music JSON",
                        key = "music_json",
                        summary = "Paste your music library JSON here",
                        defaultValue = ""
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
        if (jsonText == null) return
        if (jsonText.length == 0) return
        
        try {
            // Manual JSON parsing to avoid @Serializable issues
            val jsonElement = json.parseToJsonElement(jsonText).jsonObject
            val tracksArray = jsonElement["tracks"]?.jsonArray ?: return
            
            tracksData.clear()
            for (trackElement in tracksArray) {
                val track = trackElement.jsonObject
                
                // Manual toLong conversion to avoid Kotlin stdlib
                val durationStr = track["duration"]?.jsonPrimitive?.content
                var duration: Long? = null
                if (durationStr != null) {
                    try {
                        duration = java.lang.Long.parseLong(durationStr)
                    } catch (e: NumberFormatException) {
                        duration = null
                    }
                }
                
                val trackData = TrackData(
                    fileId = track["fileId"]?.jsonPrimitive?.content ?: continue,
                    title = track["title"]?.jsonPrimitive?.content ?: "",
                    artist = track["artist"]?.jsonPrimitive?.content ?: "",
                    album = track["album"]?.jsonPrimitive?.content ?: "",
                    albumArt = track["albumArt"]?.jsonPrimitive?.content,
                    year = track["year"]?.jsonPrimitive?.content,
                    genre = track["genre"]?.jsonPrimitive?.content,
                    duration = duration
                )
                tracksData.add(trackData)
            }
            organizeIntoAlbums()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        if (albumsCache.isEmpty()) {
            organizeIntoAlbums()
        }

        val albums = albumsCache.values.sortedBy { it.name }.map { albumData ->
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
            PagedData.Single { listOf<Shelf>(shelf) }.toFeedData()
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
        val directUrl = getDriveDirectUrl(track.id)

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
        val directUrl = getDriveDirectUrl(streamable.id)

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

    private fun getDriveDirectUrl(fileId: String): String {
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }
}

/*
 * DEPENDENCIES in ext/build.gradle.kts:
 * 
 * dependencies {
 *     compileOnly(libs.echo.common)
 *     
 *     implementation("com.squareup.okhttp3:okhttp:4.11.0")
 *     implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
 *     
 *     testImplementation(libs.junit)
 *     testImplementation(libs.coroutines.test)
 *     testImplementation(libs.echo.common)
 * }
 * 
 * EXAMPLE JSON:
 * {
 *   "tracks": [
 *     {
 *       "fileId": "1ABC123XYZ",
 *       "title": "Hey Jude",
 *       "artist": "The Beatles",
 *       "album": "Hey Jude",
 *       "albumArt": "https://i.imgur.com/heyjude.jpg",
 *       "year": "1968",
 *       "genre": "Rock",
 *       "duration": 431
 *     }
 *   ]
 * }
 * 
 * KEY FIXES:
 * ✅ Removed @Serializable annotations (they generate lazy code that triggers IllegalAccessError)
 * ✅ Manual JSON parsing using kotlinx.json primitives
 * ✅ Uses only safe String operations (.length, not .isEmpty())
 * ✅ No Kotlin stdlib string extensions
 */