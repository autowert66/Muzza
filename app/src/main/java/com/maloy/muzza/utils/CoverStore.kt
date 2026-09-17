package com.maloy.muzza.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

private val ytimgVideoIdRegex = Regex("i\\.ytimg\\.com/vi/([^/]+)/")

/**
 * Normalizes a cover/thumbnail URL so that every size variant of the same artwork
 * (e.g. `resize(200, 200)` vs `resize(1080, 1080)`) maps to a single stable key.
 */
fun canonicalCoverUrl(url: String): String {
    if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
        val index = listOf(url.indexOf("=w"), url.indexOf("=s"), url.indexOf("=h"))
            .filter { it >= 0 }
            .minOrNull()
        return if (index != null) url.substring(0, index) else url
    }
    val videoId = ytimgVideoIdRegex.find(url)?.groupValues?.getOrNull(1)
    if (videoId != null) return "https://i.ytimg.com/vi/$videoId/default.jpg"
    return url
}

/**
 * Persists one canonical, full-resolution cover per artwork under `filesDir/song_covers`
 * and serves it back to Coil through [OfflineCoverInterceptor] for any requested size.
 */
object CoverStore {
    private const val DIRECTORY = "song_covers"
    private val client = OkHttpClient()
    private val semaphore = Semaphore(4)

    fun coverFile(context: Context, url: String): File {
        val name = sha1(canonicalCoverUrl(url)) + ".jpg"
        return File(File(context.filesDir, DIRECTORY), name)
    }

    suspend fun ensure(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        val file = coverFile(context, url)
        if (file.exists()) return
        semaphore.withPermit {
            if (file.exists()) return@withPermit
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.bytes() ?: error("Empty body")
                    }
                }
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(file)) {
                        file.writeBytes(bytes)
                        temporary.delete()
                    }
                }
            }.onFailure {
                Timber.w(it, "Failed to cache cover %s", url)
            }
        }
    }

    fun delete(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        coverFile(context, url).delete()
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
