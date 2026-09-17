package com.maloy.muzza.utils

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.Comparator
import java.util.TreeSet

/**
 * Same least-recently-used eviction as media3's [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor],
 * except the byte limit is read from [maxSizeBytes] on every callback. That way changing the song cache
 * preference takes effect while the app is running instead of only after a restart.
 */
class DynamicSizeLruCacheEvictor(
    private val maxSizeBytes: () -> Long,
) : CacheEvictor {
    private val leastRecentlyUsed = TreeSet(Comparator<CacheSpan>(::compare))
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        val maxSize = maxSizeBytes()
        while (currentSize + requiredSpace > maxSize && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }

    private fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
        val lastTouchTimestampDelta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        if (lastTouchTimestampDelta == 0L) {
            return lhs.compareTo(rhs)
        }
        return if (lastTouchTimestampDelta < 0) -1 else 1
    }
}
