package dev.brahmkshatriya.echo.extension.googledrive

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.Extensions.* // ✨ CORRECTED: Added this crucial import
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

class GoogleDriveExtension : ExtensionClient, TrackClient, HomeFeedClient, AlbumClient {

    private val client = OkHttpClient()
    private val metadataCache = ConcurrentHashMap<String, FileMetadata>()

    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }
    
    private val metadataExtractionEnabled get() = setting.getBoolean("extract_metadata") ?: true


    // --- 1. Custom Extension Settings ---

    override suspend fun getSettingItems() = listOf(
        SettingHeader("Google Drive Link Settings"), 
        SettingSwitch(
            "Extract Metadata & Cache",
            "extract_metadata",
            "Downloads the first 500KB of the file to extract ID3 tags (Title, Album, Art) for better organization on the Home Feed.",
            metadataExtractionEnabled
        )
    )
    
    // --- 2. TrackClient Implementation ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val pastedUrl = track.id
        val fileId = extractFileId(pastedUrl)
            ?: throw ClientException.NotSupported("Invalid Google Drive URL or missing file ID.")

        var metadata = metadataCache[fileId] ?: FileMetadata(
            title = extractTitlePlaceholder(pastedUrl),
            artist = "Google Drive",
            album = "Pasted Links",
            coverArtUrl = null,
            fileId = fileId,
            originalUrl = pastedUrl
        )
        
        if (metadataExtractionEnabled && metadataCache[fileId] == null) {
            val resolvedUrl = resolveGoogleDriveStream(fileId)
            val extracted = extractMetadata(resolvedUrl, fileId, pastedUrl)
            metadataCache[fileId] = extracted.metadata
            metadata = extracted.metadata
        }
        
        return Track(
            id = pastedUrl,
            title = metadata.title,
            artists = listOf(Artist(metadata.artist, metadata.artist, null, null)),
            album = Album( 
                id = metadata.album.hashCode().toString(),
                title = metadata.album,
                artist = metadata.artist,
                type = Album.Type.Album,
                cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) },
                trackCount = 0L
            ),
            cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) }, 
            streamable = Streamable(
                id = fileId, 
                type = Streamable.MediaType.Server,
                extras = mapOf("file_id" to fileId)
            )
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        if (streamable.type != Streamable.MediaType.Server) {
            throw IllegalStateException("Unsupported Streamable type: ${streamable.type}")
        }
        
        val fileId = streamable.extras["file_id"]
            ?: throw ClientException.NotSupported("Missing file ID in streamable extras.")

        val directStreamUrl = resolveGoogleDriveStream(fileId)

        return Streamable.Source.Http(
            request = directStreamUrl.toGetRequest()
        ).toSource(fileId).toMedia()
    }
    
    // Stub implementation to fulfill the interface contract
    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null


    // --- 3. HomeFeedClient Implementation ---

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        if (!metadataExtractionEnabled) {
            return Feed(listOf()).toFeed() // CORRECTED: Using .toFeed() from Extensions
        }

        val albums = metadataCache.values
            .filter { it.album != "Pasted Links" }
            .groupBy { it.album }
            .map { (albumTitle, tracks) ->
                val albumId = "gdrive_album:${albumTitle.hashCode()}" 
                
                val cover = tracks.first().coverArtUrl?.let { 
                    ImageHolder.NetworkRequestImageHolder(it.toGetRequest())
                }

                Album(
                    id = albumId,
                    title = albumTitle,
                    artist = tracks.first().artist,
                    cover = cover,
                    type = Album.Type.Album,
                    trackCount = tracks.size.toLong()
                ).toShelf() 
            }
        
        if (albums.isEmpty()) return Feed(listOf()).toFeed()
        
        return Feed(
            shelves = listOf(
                Shelf.Lists.Items(
                    id = "gdrive_albums",
                    title = "Pasted Drive Albums",
                    list = albums.map { it.list.first() } 
                )
            )
        ).toFeed() // CORRECTED: Using .toFeed() from Extensions
    }


    // --- 4. AlbumClient Implementation ---

    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    // Stub implementation to fulfill the interface contract
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null 

    override suspend fun loadTracks(album: Album) = paged { // CORRECTED: Removed unused 'offset' parameter
        val tracksInAlbum = metadataCache.values
            .filter { it.album == album.title }
            .map { metadata ->
                Track(
                    id = metadata.originalUrl, 
                    title = metadata.title,
                    artists = listOf(Artist(metadata.artist, metadata.artist, null, null)), 
                    album = Album(
                        id = metadata.album.hashCode().toString(),
                        title = metadata.album,
                        artist = metadata.artist,
                        type = Album.Type.Album,
                        cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) },
                        trackCount = 0L 
                    ), 
                    cover = metadata.coverArtUrl?.let { ImageHolder.NetworkRequestImageHolder(it.toGetRequest()) },
                    streamable = Streamable(
                        id = metadata.fileId!!, 
                        type = Streamable.MediaType.Server,
                        extras = mapOf("file_id" to metadata.fileId!!) 
                    )
                )
            }

        tracksInAlbum to null 
    }.toFeed()


    // --- 5. Metadata Extraction Logic ---

    data class FileMetadata(
        var title: String,
        var artist: String,
        var album: String,
        var coverArtUrl: String?,
        var fileId: String? = null,
        var originalUrl: String = ""
    )
    
    private data class MetadataExtractionResult(val metadata: FileMetadata, val success: Boolean)

    companion object {
        private const val FILE_HEADER_SIZE = 500 * 1024 
    }
    
    private suspend fun extractMetadata(streamUrl: String, fileId: String, originalUrl: String): MetadataExtractionResult {
        val rangeHeader = "bytes=0-${FILE_HEADER_SIZE - 1}"
        val request = Request.Builder()
            .url(streamUrl)
            .header("Range", rangeHeader)
            .build()

        val tempFile = File.createTempFile("gd_metadata_", ".tmp")
        
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    throw Exception("Failed to download file header.")
                }
                
                response.body!!.byteStream().use { inputStream ->
                    tempFile.outputStream().use { output ->
                        inputStream.copyTo(output, FILE_HEADER_SIZE.toLong())
                    }
                }
                
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag
                
                val title = tag.firstTitle.takeIf { !it.isNullOrBlank() } ?: extractTitlePlaceholder(originalUrl)
                val artist = tag.firstArtist.takeIf { !it.isNullOrBlank() } ?: "Unknown Artist"
                val album = tag.firstAlbum.takeIf { !it.isNullOrBlank() } ?: "Pasted Links"
                
                val coverArtUrl = tag.firstArtwork?.let { artwork ->
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
            tempFile.delete()
        }
    }
    
    // Helper extension function for safe copy with limit
    private fun java.io.InputStream.copyTo(out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead = 0L
        var read: Int = 0 // CORRECTED: Initialized 'read'
        while (bytesRead < limit && read(buffer).also { read = it } >= 0) {
            val bytesToWrite = minOf(read.toLong(), limit - bytesRead).toInt()
            out.write(buffer, 0, bytesToWrite)
            bytesRead += bytesToWrite
        }
    }


    // --- 6. Unchanged Helper Functions ---

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
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }
}
