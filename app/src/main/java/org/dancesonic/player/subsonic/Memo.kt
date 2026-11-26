package org.dancesonic.player.subsonic

/**
 * A small thread-safe cache that keeps the [max] most recently used values (#134). HTTP clients and Retrofit instances
 * are built once per key with it, so their connection pools, TLS sessions and parsed service methods are reused.
 */
internal class Memo<K : Any, V : Any>(
    private val max: Int,
    private val create: (K) -> V,
) {
    private val values =
        object : LinkedHashMap<K, V>(max, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }

    /** The value for [key], built on first use. */
    @Synchronized
    operator fun get(key: K): V = values.getOrPut(key) { create(key) }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
