package org.dancesonic.player.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.account.AccountClient
import org.dancesonic.player.account.AccountException
import org.dancesonic.player.data.SyncMode
import org.dancesonic.player.data.SyncResult

// Helpers of ConnectViewModel that need none of its state.

private const val COUNTDOWN_TICK_MILLIS = 1_000L

/** The line Config shows after a sync. */
internal fun SyncResult.summary(): String {
    val danceNote = if (danceLookupsFailed > 0) ", ${count(danceLookupsFailed, "dance lookup")} failed" else ""
    return when (mode) {
        SyncMode.Unchanged -> "No changes on the server since the last sync."
        SyncMode.Changes -> "Applied ${count(rowsWritten, "change")}$danceNote"
        SyncMode.Full ->
            "Synced ${count(artists, "artist")}, ${count(albums, "album")}, ${count(tracks, "track")}$danceNote"
    }
}

/** Runs [block] while [show] gets the time left until [expiresAt], once a second (#68). */
internal suspend fun <T> countingDown(
    expiresAt: Long,
    show: (String) -> Unit,
    block: suspend () -> T,
): T =
    coroutineScope {
        val ticker =
            launch {
                while (true) {
                    show(waitingStatus(expiresAt - System.currentTimeMillis()))
                    delay(COUNTDOWN_TICK_MILLIS)
                }
            }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }

/** Ends a sign-in session, best effort: the key is already issued and the session expires on its own. */
@Suppress("SwallowedException")
internal suspend fun AccountClient.logoutQuietly(
    url: HttpUrl,
    session: String,
) {
    try {
        logout(url, session)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AccountException) {
        // Deliberately ignored, see above.
    }
}
