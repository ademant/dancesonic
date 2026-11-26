package org.dancesonic.player.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the one connection of [PlayerController] to the playback service (#130). [get] connects once and concurrent
 * first calls share that connection. [reset] tears it down under the same lock, so a connect that is still pending is
 * torn down as soon as it completes and cannot outlive the reset.
 *
 * [generation] changes with every reset. Work that started before a reset, such as a queue for the previous server,
 * compares the generation it saw with [isCurrent] and stops when they differ.
 */
internal class ControllerSlot<T : Any>(
    private val connect: suspend () -> T,
) {
    private val lock = Mutex()
    private val resets = AtomicLong()

    @Volatile
    private var current: T? = null

    /** The connection, or null when none is open. */
    val connection: T? get() = current

    /** The count of resets so far. Read it before work that a reset should cancel. */
    val generation: Long get() = resets.get()

    /** Whether no reset happened since [generation] was read. */
    fun isCurrent(generation: Long): Boolean = resets.get() == generation

    /** The open connection, or a new one; [onConnected] runs once for a new one, inside the lock. */
    suspend fun get(onConnected: (T) -> Unit = {}): T =
        lock.withLock {
            current ?: connect().also {
                onConnected(it)
                current = it
            }
        }

    /** Marks every earlier [generation] as stale, at once. [close] then closes the connection. */
    fun invalidate() {
        resets.incrementAndGet()
    }

    /**
     * Closes the connection with [teardown] once it can take the lock, so a pending [get] completes first and is
     * closed right after. Safe to call repeatedly; [teardown] runs only for a connection that is open.
     */
    suspend fun close(teardown: (T) -> Unit) {
        lock.withLock {
            val open = current ?: return@withLock
            current = null
            teardown(open)
        }
    }

    /** [invalidate], then [close]. */
    suspend fun reset(teardown: (T) -> Unit) {
        invalidate()
        close(teardown)
    }
}
