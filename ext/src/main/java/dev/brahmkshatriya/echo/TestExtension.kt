package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException // Added for file handling exceptions

// --- Data Model for your JSON file ---
@Serializable
data class DriveTrackMetadata(
    val id: String, // Google Drive File ID
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val publicDriveLink: String, // The critical link for streaming
    val coverArtUrl: String, // Public URL for album art
    val durationSeconds: Long
)

class GoogleDriveExtension : ExtensionClient, TrackClient, LibraryFeedClient {

    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    // !! IMPORTANT !! You must change this path to point to your actual JSON file
    // Example: File("/sdcard/Echo/GoogleDriveMetadata.json")
    private val metadataFile: File = File("path/to/your/metadata.json")
    
    // The JSON parser configuration
    private val json = Json { ignoreUnknownKeys = true }

    // --- Core Logic: Loading Metadata from JSON ---

    private fun loadAllMetadata(): List<DriveTrackMetadata> {
        return try {
            val jsonString = metadataFile.readText()
            json.decodeFromString<List<DriveTrackMetadata>>(jsonString)
        } catch (e: IOException) {
            // Throws a client exception if the file cannot be read
            throw ClientException.Internal(
                "Metadata file not found or failed to read at ${metadataFile.absolutePath}: ${e.message}"
            )
        } catch (e: Exception) {
             // Throws a client exception if JSON parsing fails
            throw ClientException.Internal(
                "Failed to parse metadata JSON file. Check format: ${e.message}"
            )
        }
    }

    // --- LibraryFeedClient Implementation: Grouped by Album ---
    
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val metadataList = loadAllMetadata()

        // 1. Group all tracks by their album title
        val albumsGrouped = metadataList.groupBy { it.albumTitle }

        // 2. Map each group of tracks (each album) into a Shelf
        val albumShelves = albumsGrouped.map { (albumTitle, tracksInAlbum) ->
            
            val firstTrack = tracksInAlbum.first()
            val albumArtistName = firstTrack.artistName
            val albumCoverUrl = firstTrack.coverArtUrl

            // 3. Convert the list of metadata objects into a list of Track objects
            val tracks = tracksInAlbum.map { meta ->
                Track(
                    id = meta.id,
                    title = meta.title,
                    duration = meta.durationSeconds,
                    artist = Artist(
                        id = "artist:${meta.artistName.hashCode()}",
                        title = meta.artistName
                    ),
                    album = Album(
                        id = "album:${meta.albumTitle.hashCode()}",
                        title = meta.albumTitle,
                    ),
                    cover = ImageHolder.NetworkRequestImageHolder(
                        meta.coverArtUrl.toGetRequest()
                    ),
                    // Stores the drive link for the TrackClient to use later
                    extras = mapOf("driveLink" to meta.publicDriveLink)
                )
            }
            
            // 4. Create a Shelf.Lists.Tracks for this album
            Shelf.Lists.Tracks(
                id = "album:${albumTitle.hashCode()}", // Unique ID for the shelf
                title = albumTitle, // Shelf title is the album name
                subtitle = albumArtistName, // Shelf subtitle is the artist name
                list = tracks
            )
        }

        // Return a Feed with all the album shelves
        return PagedData.Single { albumShelves }.toFeed()
    }


    // --- TrackClient Implementation: Streaming Setup ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        // Retrieve the stream link from the track's extras
        val driveLink = track.extras["driveLink"]
            ?: throw ClientException.Internal("Missing 'driveLink' for track ${track.id}. Cannot stream.")

        // Create the Streamable Source using the HTTP GET request to the public link
        val source = Streamable.Source.Http(
            request = driveLink.toGetRequest(),
            decryption = Streamable.Decryption.None // Assumes the file is not encrypted
        )

        // Convert the source into the final Streamable Media object
        val media = source.toMedia()

        // Return the track with the streamable media attached
        return track.copy(streamable = media)
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        // This function confirms the media type is 'Server' and returns the Source as Media
        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                val source = streamable.sources.firstOrNull() 
                    ?: throw ClientException.NotSupported("No streamable source found in the Track object.")
                source.toMedia()
            }
            else -> throw IllegalStateException("Unsupported Streamable type: ${streamable.type}")
        }
    }
}
