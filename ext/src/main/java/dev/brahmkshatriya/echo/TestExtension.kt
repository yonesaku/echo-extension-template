package dev.brahmkshatriya.echo.extension

// Corrected Imports
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
// Corrected ClientException import
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// --- Data Model for your JSON file ---
@Serializable
data class DriveTrackMetadata(
    val id: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val publicDriveLink: String,
    val coverArtUrl: String,
    val durationSeconds: Long
)

// Class 'GoogleDriveExtension' is not abstract and does not implement abstract members:
// -> Added missing functions and fixed ClientException.Internal reference
class GoogleDriveExtension : ExtensionClient, TrackClient, LibraryFeedClient {

    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    // FIX 1: Implements the missing function from ExtensionClient
    override suspend fun getSettingItems(): List<Setting> = emptyList()

    // FIX 2: Implements the missing function from TrackClient (no related tracks for now)
    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    private val metadataFile: File = File("path/to/your/metadata.json")
    private val json = Json { ignoreUnknownKeys = true }

    // --- Core Logic: Loading Metadata from JSON ---

    private fun loadAllMetadata(): List<DriveTrackMetadata> {
        return try {
            val jsonString = metadataFile.readText()
            json.decodeFromString<List<DriveTrackMetadata>>(jsonString)
        } catch (e: IOException) {
            // FIX 3: Corrected ClientException.Internal reference
            throw ClientException.Internal(
                "Metadata file not found or failed to read at ${metadataFile.absolutePath}: ${e.message}"
            )
        } catch (e: Exception) {
             // FIX 4: Corrected ClientException.Internal reference
            throw ClientException.Internal(
                "Failed to parse metadata JSON file. Check format: ${e.message}"
            )
        }
    }

    // --- LibraryFeedClient Implementation: Grouped by Album ---
    
    override suspend fun loadLibraryFeed(): Feed<Shelf> { // Return type fixed here
        val metadataList = loadAllMetadata()

        val albumsGrouped = metadataList.groupBy { it.albumTitle }

        val albumShelves = albumsGrouped.map { (albumTitle, tracksInAlbum) ->
            
            val firstTrack = tracksInAlbum.first()
            val albumArtistName = firstTrack.artistName
            val albumCoverUrl = firstTrack.coverArtUrl

            val tracks = tracksInAlbum.map { meta ->
                Track(
                    id = meta.id,
                    title = meta.title,
                    duration = meta.durationSeconds,
                    // FIX 5: Corrected Artist constructor call (removed 'artist' parameter)
                    artist = Artist(
                        id = "artist:${meta.artistName.hashCode()}",
                        title = meta.artistName // Use 'title' for the name parameter
                    ),
                    album = Album(
                        id = "album:${meta.albumTitle.hashCode()}",
                        title = meta.albumTitle,
                    ),
                    // FIX 6: Corrected ImageHolder.NetworkRequestImageHolder constructor (added 'crop' parameter)
                    cover = ImageHolder.NetworkRequestImageHolder(
                        request = meta.coverArtUrl.toGetRequest(),
                        crop = false // Assuming no cropping needed for album art
                    ),
                    extras = mapOf("driveLink" to meta.publicDriveLink)
                )
            }
            
            Shelf.Lists.Tracks(
                id = "album:${albumTitle.hashCode()}",
                title = albumTitle,
                subtitle = albumArtistName,
                list = tracks
            )
        }

        // FIX 7: Fixed return type mismatch by returning Feed<Shelf>
        return PagedData.Single { albumShelves }.toFeed()
    }


    // --- TrackClient Implementation: Streaming Setup ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val driveLink = track.extras["driveLink"]
            // FIX 8: Corrected ClientException.Internal reference
            ?: throw ClientException.Internal("Missing 'driveLink' for track ${track.id}. Cannot stream.")

        val source = Streamable.Source.Http(
            request = driveLink.toGetRequest(),
            // FIX 9: Corrected access to Streamable.Decryption.None
            decryption = Streamable.Decryption.None
        )

        val media = source.toMedia()

        return track.copy(streamable = media)
    }

    // FIX 10: Corrected function signature (removed 'streamable' parameter name from lambda type)
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                // FIX 11: Corrected access to 'sources' on Streamable
                val source = streamable.sources.firstOrNull() 
                    ?: throw ClientException.NotSupported("No streamable source found in the Track object.")
                source.toMedia()
            }
            else -> throw IllegalStateException("Unsupported Streamable type: ${streamable.type}")
        }
    }
}
