package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class DriveLinkExtension : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private val json = Json { ignoreUnknownKeys = true }

    private var tracksData = java.util.ArrayList<TrackData>()
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

    val albumValues = java.util.ArrayList(albumsCache.values)
    java.util.Collections.sort(albumValues, Comparator { a, b -> a.name.compareTo(b.name) })

    val albums = java.util.ArrayList<Album>()
    for (albumData in albumValues) {
        val cover = if (albumData.artwork != null) {
            NetworkRequestImageHolder(
                request = NetworkRequest(url = albumData.artwork, headers = emptyMap()),
                crop = false
            )
        } else null

        albums.add(
            Album(
                id = albumData.name,
                title = albumData.name,
                cover = cover,
                artists = java.util.Collections.singletonList(
                    Artist(
                        id = albumData.artist,
                        name = albumData.artist
                    )
                ),
                subtitle = buildAlbumSubtitle(albumData)
            )
        )
    }

    val shelf = Shelf.Lists.Items(
        id = "albums",
        title = "Albums",
        list = albums
    )

    // Cast to Shelf to fix type mismatch
    // NOTE: Check for typo here, should likely be java.util.Collections.singletonList
    val shelves: List<Shelf> = java.util.Collections.singletonList(shelf as Shelf) 

    // FIX: Using the suspend lambda that the compiler expects
    return Feed(java.util.Collections.emptyList()) { _: Tab? -> 
        PagedData.Single { shelves }.toFeedData()
    }
}




    private fun buildAlbumSubtitle(albumData: AlbumData): String {
        val parts = java.util.ArrayList<String>()
        if (albumData.year != null) parts.add(albumData.year)
        if (albumData.genre != null) parts.add(albumData.genre)
        parts.add(albumData.tracks.size.toString() + " tracks")

        // Manual join
        val result = StringBuilder()
        for (i in 0 until parts.size) {
            result.append(parts[i])
            if (i < parts.size - 1) {
                result.append(" • ")
            }
        }
        return result.toString()
    }

    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val albumData = albumsCache[album.id]
        if (albumData == null) return null

        val tracks = java.util.ArrayList<Track>()
        for (trackData in albumData.tracks) {
            val albumCover = if (albumData.artwork != null) {
                NetworkRequestImageHolder(
                    request = NetworkRequest(url = albumData.artwork, headers = emptyMap()),
                    crop = false
                )
            } else null

            val trackCover = if (trackData.albumArt != null) {
                NetworkRequestImageHolder(
                    request = NetworkRequest(url = trackData.albumArt, headers = emptyMap()),
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
                    album = Album(
                        id = albumData.name,
                        title = albumData.name,
                        cover = albumCover
                    ),
                    duration = trackData.duration,
                    cover = trackCover
                )
            )
        }

        val pagedData = PagedData.Single<Track> { tracks }
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

        val networkRequest = NetworkRequest(
            url = directUrl,
            headers = emptyMap()
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

    private fun organizeIntoAlbums() {
        albumsCache.clear()

        for (trackData in tracksData) {
            val albumName = trackData.album
            val artistName = trackData.artist

            val albumData = albumsCache.getOrPut(albumName) {
                AlbumData(
                    name = albumName,
                    artist = artistName,
                    year = trackData.year,
                    genre = trackData.genre,
                    artwork = trackData.albumArt,
                    tracks = java.util.ArrayList()
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
 * 
 * NOTE: Duration should be in SECONDS (not milliseconds)
 * 
 * FEATURES:
 * ✅ JSON-based metadata (no file scanning needed)
 * ✅ Album art from URLs
 * ✅ Streams directly from Google Drive
 * ✅ Organized by albums on home screen
 * ✅ Full metadata support (title, artist, album, year, genre, duration)
 */