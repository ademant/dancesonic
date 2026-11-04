package org.dancesonic.player.data

import android.util.Log

/**
 * Receives how long each phase of a sync took, for tuning. Phase names and durations only: never URLs,
 * keys or item names.
 */
fun interface SyncTimings {
    fun record(
        phase: String,
        millis: Long,
    )

    companion object {
        /** Records nothing. Used in release builds. */
        val None = SyncTimings { _, _ -> }
    }
}

/** Writes each timing to logcat, tag [TAG], at debug level. Use in debug builds only. */
class LogcatSyncTimings : SyncTimings {
    override fun record(
        phase: String,
        millis: Long,
    ) {
        Log.d(TAG, "$phase $millis ms")
    }

    companion object {
        const val TAG = "DanceSonicSync"
    }
}
