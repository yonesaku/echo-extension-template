package dev.yourname.echo.extension

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.brahmkshatriya.echo.data.models.*
import dev.brahmkshatriya.echo.extensionapi.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class DriveAudioExtension(private val context: Context) : Extension {

    // Data models
    data class Track(val title: String, val driveUrl: String) {
        val id: String get() = title.hashCode().toString()
        fun toMediaItem(): MediaItem = MediaItem(
            id = id,
            title = title,
            artist = null,
            isPlayable = true
        )
    }

    data class Album(val title: String, val artist: String, val tracks: List<Track>) {
        fun toMediaItem(): MediaItem = MediaItem(
            id = title,
            title = title,
            artist = artist,
            children = tracks.map { it.toMediaItem() }
        )
    }

    // Metadata loader
    private fun loadMetadata(): List<Album> {
        val input = context.assets.open("metadata.json")
        val reader = InputStreamReader(input)
        val type = object : TypeToken<List<Album>>() {}.type
        return Gson().fromJson(reader, type)
    }

    // Link resolver
    private fun resolveDriveLink(driveUrl: String): String {
        val fileId = driveUrl.substringAfter("/d/").substringBefore("/")
        return "https://docs.google.com/uc?export=download&id=$fileId"
    }

    // Cache manager
    private val prefs: SharedPreferences =
        context.getSharedPreferences("drive_audio_cache", Context.MODE_PRIVATE)

    private fun getCachedStream(id: String): Stream? {
        val json = prefs.getString(id, null) ?: return null
        return Gson().fromJson(json, Stream::class.java)
    }

    private fun saveStream(id: String, stream: Stream) {
        prefs.edit().putString(id, Gson().toJson(stream)).apply()
    }

    // Extension logic
    private val metadata by lazy { loadMetadata() }

    override suspend fun getHome(): List<HomePage> = listOf(
        HomePage("Albums", metadata.map { it.toMediaItem() })
    )

    override suspend fun getItem(item: MediaItem): MediaItem {
        val album = metadata.find { it.title == item.title }
        return album?.toMediaItem() ?: item
    }

    override suspend fun getStreams(item: MediaItem): List<Stream> {
        val cached = getCachedStream(item.id)
        if (cached != null) return listOf(cached)

        val track = metadata.flatMap { it.tracks }.find { it.id == item.id }
        val url = track?.driveUrl?.let { resolveDriveLink(it) } ?: return emptyList()
        val stream = Stream(url, StreamType.DIRECT)
        saveStream(item.id, stream)
        return listOf(stream)
    }
}