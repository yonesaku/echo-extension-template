package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

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
            ),
            SettingSwitch(
                title = "Enabled",
                key = "enabled",
                summary = "Enable the extension",
                defaultValue = true
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

        val albums = albumsCache.values
    .toMutableList()
    .apply { sortWith(compareBy { it.name }) }
    .map { albumData ->
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

        val shelf = Shelf.Lists.Items(
            id = "albums",
            title = "Albums",
            list = albums
        )

        val pagedData = PagedData.Single<Shelf> { listOf(shelf) }
        return pagedData.toFeed()
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

        val pagedData = PagedData.Single { tracks }
        return pagedData.toFeed()
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
        
        // Create NetworkRequest for the Drive URL
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
 * DEPENDENCIES in build.gradle.kts:
 * implementation("com.squareup.okhttp3:okhttp:4.11.0")
 * implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
 * 
 * EXAMPLE JSON TO PASTE IN SETTINGS:
 * 
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
 */