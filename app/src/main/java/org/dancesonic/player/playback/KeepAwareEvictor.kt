package org.dancesonic.player.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/**
 * Least-recently-used eviction that never removes a kept song. Over [maxBytes], the oldest spans of songs that are
 * not kept are removed first. Kept songs may take the cache past the cap; that is what keeping them means.
 * Callers hold the cache's lock, so the state here is guarded by [Synchronized].
 */
@Suppress("UnsafeOptInUsageError") // Media3 cache classes are marked unstable; see PlayerController.
internal class KeepAwareEvictor(
    private val maxBytes: Long,
    private val isKept: (songId: String) -> Boolean,
) : CacheEvictor {
    /** Cached spans, oldest first. Re-inserting a span moves it to the end, so the order is the order of use. */
    private val spans = LinkedHashMap<String, CacheSpan>()
    private var totalBytes = 0L
    private var seeded = false

    override fun requiresCacheSpanTouches(): Boolean = true

    /** The spans already on disk are read when the first file opens, because the cache is not passed in here. */
    override fun onCacheInitialized() = Unit

    @Synchronized
    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        if (!seeded) {
            seeded = true
            cache.keys.forEach { songId -> cache.getCachedSpans(songId).forEach(::remember) }
        }
        evict(cache)
    }

    @Synchronized
    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        remember(span)
        evict(cache)
    }

    @Synchronized
    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        spans.remove(identity(span))?.let { totalBytes -= it.length }
    }

    @Synchronized
    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        spans.remove(identity(oldSpan))?.let { totalBytes -= it.length }
        remember(newSpan)
    }

    /** Adds a cached span as the most recent. Idempotent, so a span seen twice is counted once. */
    private fun remember(span: CacheSpan) {
        if (!span.isCached) return
        spans.remove(identity(span))?.let { totalBytes -= it.length }
        spans[identity(span)] = span
        totalBytes += span.length
    }

    private fun evict(cache: Cache) {
        val victims =
            chooseVictims(
                oldestFirst = spans.values.toList(),
                songId = { it.key },
                length = { it.length },
                bytesToFree = totalBytes - maxBytes,
                isKept = isKept,
            )
        // Removing a span calls back into onSpanRemoved, which updates [spans]; the victims were copied first.
        victims.forEach(cache::removeSpan)
    }

    private fun identity(span: CacheSpan): String = "${span.key}\u0000${span.position}"
}

/**
 * The spans to remove to free [bytesToFree]: the oldest first, skipping kept songs. Pure, so the rule is tested
 * without the cache. Returns nothing when [bytesToFree] is not positive.
 */
internal fun <T> chooseVictims(
    oldestFirst: List<T>,
    songId: (T) -> String,
    length: (T) -> Long,
    bytesToFree: Long,
    isKept: (songId: String) -> Boolean,
): List<T> {
    val victims = mutableListOf<T>()
    var freed = 0L
    for (item in oldestFirst.asSequence().filterNot { isKept(songId(it)) }) {
        if (freed >= bytesToFree) break
        victims += item
        freed += length(item)
    }
    return victims
}
