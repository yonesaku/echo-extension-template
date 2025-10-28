package dev.brahmkshatriya.echo.extension.googledrive

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.settings.SettingHeader
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import net.jthink.jaudiotagger.audio.AudioFileIO
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// --- 1. Client Interfaces ---
class GoogleDriveExtension : ExtensionClient, TrackClient, HomeFeedClient, AlbumClient {

    private val client = OkHttpClient()
    
    // In-memory cache for metadata (File ID -> Metadata)
    private val metadataCache = ConcurrentHashMap<String, FileMetadata>()

    // Required by the extension framework
    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }
    
    // Setting state retrieved from the framework
    private val metadataExtractionEnabled get() = setting.getBoolean("extract_metadata") ?: true


    // --- 2. Custom Extension Settings ---

    override suspend fun getSettingItems() = listOf(
        SettingHeader("Google Drive Link Settings"),
        SettingSwitch(
            "Extract Metadata & Cache",
            "extract_metadata",
            "Downloads the first 500KB of the file to extract ID3 tags (Title, Album, Art) for better organization on the Home Feed.",
            metadataExtractionEnabled
        )
    )

    // --- 3. TrackClient Implementation ---

    /**
     * Called when a user pastes a link. It resolves the File ID, checks the cache,
     * and performs metadata extraction if enabled.
     */
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val pastedUrl = track.id
        val fileId = extractFileId(pastedUrl)
            ?: throw ClientException.NotSupported("Invalid Google Drive URL or missing file ID.")

        // 1. Initial/Cached Metadata
        var metadata = metadataCache[fileId] ?: FileMetadata(
            title = extractTitlePlaceholder(pastedUrl),
            artist = "Google Drive",
            album = "Pasted Links",
            coverArtUrl = null,
            fileId = fileId,
            originalUrl = pastedUrl
        )
        
        // 2. Extract and Cache Metadata if enabled and not already cached
        if (metadataExtractionEnabled && metadataCache[fileId] == null) {
            val resolvedUrl = resolveGoogleDriveStream(fileId)
            val extracted = extractMetadata(resolvedUrl, fileId, pastedUrl)
            metadataCache[fileId] = extracted.metadata
            metadata = extracted.metadata // Use the new, richer metadata
        }
        
        // 3. Build the final Track object
        return Track(
            id = pastedUrl, 
            sourceId = "google_drive_streamer",
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            // Use a synthetic URL if coverArtBytes is available (or null if not)
            cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) },
            streamable = Streamable(
                id = fileId, // File ID used for streaming resolution
                type = Streamable.MediaType.Server,
                extras = mapOf("file_id" to fileId) 
            )
        )
    }

    /**
     * Called by the player when it needs the final playable URL.
     */
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        if (streamable.type != Streamable.MediaType.Server) {
            throw IllegalStateException("Unsupported Streamable type: ${streamable.type}")
        }
        
        val fileId = streamable.extras["file_id"]
            ?: throw ClientException.NotSupported("Missing file ID in streamable extras.")

        // This is the direct streaming URL
        val directStreamUrl = resolveGoogleDriveStream(fileId)

        return Streamable.Source.Http(
            request = directStreamUrl.toGetRequest()
        ).toSource(fileId).toMedia()
    }


    // --- 4. HomeFeedClient Implementation ---

    /**
     * Displays cached links grouped as "Albums" on the Home Feed.
     */
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        if (!metadataExtractionEnabled) {
            // Return an empty feed if the feature is disabled
            return Feed(listOf()) { PagedData.Single { emptyList() }.toFeedData() }
        }

        // Group cached tracks by album title
        val albums = metadataCache.values
            .filter { it.album != "Pasted Links" } // Filter out generic tracks
            .groupBy { it.album }
            .map { (albumTitle, tracks) ->
                // The Album ID is synthetic, based on the album title
                val albumId = "gdrive_album:${albumTitle.hashCode()}" 
                
                // Get the cover art from the first track in the group
                val cover = tracks.first().coverArtUrl?.let { 
                    ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) 
                }

                // Create a synthetic Album object
                Album(
                    id = albumId,
                    title = albumTitle,
                    artist = tracks.first().artist,
                    cover = cover,
                    type = Album.Type.Album,
                    trackCount = tracks.size.toLong()
                ).toShelf() // Convert the Album to a Shelf item
            }
        
        // If there are no albums, return an empty list
        if (albums.isEmpty()) return Feed(listOf()) { PagedData.Single { emptyList() }.toFeedData() }
        
        return Feed(listOf()) {
            PagedData.Single {
                listOf(
                    Shelf.Lists.Items(
                        id = "gdrive_albums",
                        title = "Pasted Drive Albums",
                        list = albums.map { it.list.first() }
                    )
                )
            }.toFeedData()
        }
    }


    // --- 5. AlbumClient Implementation ---

    override suspend fun loadAlbum(album: Album): Album {
        // Since the Album is synthetic, we just return it
        return album
    }

    override suspend fun loadTracks(album: Album) = paged { offset ->
        // Retrieve and reconstruct all tracks belonging to this album title from the cache
        val tracksInAlbum = metadataCache.values
            .filter { it.album == album.title }
            .map { metadata ->
                Track(
                    id = metadata.originalUrl, 
                    sourceId = "google_drive_streamer",
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) },
                    streamable = Streamable(
                        id = metadata.fileId!!, 
                        type = Streamable.MediaType.Server,
                        extras = mapOf("file_id" to metadata.fileId!!) 
                    )
                )
            }

        // Return all tracks (no paging needed for in-memory cache)
        tracksInAlbum to null 
    }.toFeed()


    // --- 6. Metadata Extraction Logic (The Complex Part) ---

    // Data class to hold the extracted information
    data class FileMetadata(
        var title: String,
        var artist: String,
        var album: String,
        var coverArtUrl: String?,
        var fileId: String? = null,
        var originalUrl: String = ""
    )
    
    // Result wrapper
    private data class MetadataExtractionResult(val metadata: FileMetadata, val success: Boolean)

    // Size limit for header download (500KB)
    private const val FILE_HEADER_SIZE = 500 * 1024 
    
    private suspend fun extractMetadata(streamUrl: String, fileId: String, originalUrl: String): MetadataExtractionResult {
        // Define the byte range for the request (0 to 500KB)
        val rangeHeader = "bytes=0-${FILE_HEADER_SIZE - 1}"
        val request = Request.Builder()
            .url(streamUrl)
            .header("Range", rangeHeader)
            .build()

        // Create a temporary file to store the header bytes. JAudioTagger needs a File object.
        val tempFile = File.createTempFile("gd_metadata_", ".tmp")
        
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    throw Exception("Failed to download file header.")
                }
                
                // Write the stream to the temporary file
                response.body!!.byteStream().use { inputStream ->
                    tempFile.outputStream().use { output ->
                        inputStream.copyTo(output, FILE_HEADER_SIZE.toLong())
                    }
                }
                
                // Use JAudioTagger to read the tags from the temporary file
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag
                
                val title = tag.firstTitle.takeIf { it.isNotBlank() } ?: extractTitlePlaceholder(originalUrl)
                val artist = tag.firstArtist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                val album = tag.firstAlbum.takeIf { it.isNotBlank() } ?: "Unknown Album"
                
                val coverArtUrl = tag.firstArtwork?.let { artwork ->
                    // The standard way to handle artwork is to convert it to a Base64 Data URL
                    // that the Echo framework can render directly.
                    "data:${artwork.mimeType};base64," + java.util.Base64.getEncoder().encodeToString(artwork.binaryData)
                }

                val metadata = FileMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    coverArtUrl = coverArtUrl,
                    fileId = fileId,
                    originalUrl = originalUrl
                )
                
                MetadataExtractionResult(metadata, true)
            }
        } catch (e: Exception) {
            println("Metadata extraction failed for $fileId: ${e.message}")
            // Return fallback metadata on failure
            MetadataExtractionResult(
                FileMetadata(
                    title = "Error: ${extractTitlePlaceholder(originalUrl)}",
                    artist = "Unknown Artist",
                    album = "Error Reading",
                    coverArtUrl = null,
                    fileId = fileId,
                    originalUrl = originalUrl
                ), false
            )
        } finally {
            tempFile.delete() // Ensure the temporary file is always deleted
        }
    }
    
    // Helper extension function for safe copy with limit
    private fun java.io.InputStream.copyTo(out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead = 0L
        var read: Int
        while (bytesRead < limit && read(buffer).also { read = it } >= 0) {
            val bytesToWrite = minOf(read.toLong(), limit - bytesRead).toInt()
            out.write(buffer, 0, bytesToWrite)
            bytesRead += bytesToWrite
        }
    }


    // --- 7. Unchanged Helper Functions ---

    private fun extractFileId(url: String): String? {
        val uri = runCatching { URL(url).toURI() }.getOrNull() ?: return null
        val pathSegments = uri.path.split('/')
        if (pathSegments.size >= 3 && pathSegments[1] == "file" && pathSegments[2] == "d") {
            return pathSegments.getOrNull(3)
        }
        val query = uri.query ?: return null
        val params = query.split('&').associate { it.split('=').let { p -> p[0] to p.getOrNull(1) } }
        return params["id"]
    }
    
    private fun extractTitlePlaceholder(url: String): String {
        return "Google Drive File (${url.takeLast(10)})"
    }

    private fun resolveGoogleDriveStream(fileId: String): String {
        // Direct download trick to bypass preview page
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }
}
