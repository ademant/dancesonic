package org.dancesonic.player.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicException

/** Shown when the user kept the current connection instead of the scanned one. */
internal const val REPLACE_DECLINED = "The current connection was kept. Nothing was changed."

/** A scanned code that would point the app at another server, shown for the user to confirm (#135). */
data class ServerReplacement(
    /** The host of the stored connection. */
    val currentHost: String,
    /** The host the scanned code names. */
    val newHost: String,
)

/** The replacement to confirm when [scanned] names another server than [stored], or null when nothing is replaced. */
internal fun replacementOf(
    stored: ServerSettings?,
    scanned: HttpUrl,
): ServerReplacement? {
    if (stored == null || stored.baseUrl == scanned) return null
    return ServerReplacement(currentHost = stored.baseUrl.displayHost(), newHost = scanned.displayHost())
}

/** Host and, when it is not the default one, port: two servers on one host differ only there. */
private fun HttpUrl.displayHost(): String = if (port == HttpUrl.defaultPort(scheme)) host else "$host:$port"

/**
 * The user's confirmation that a scanned code may replace the stored connection (#135). [await] shows the change
 * through [show] and waits for the answer that [answer] records. [show] receives null once nothing is to be shown.
 * No answer in time is a no: the stored connection stays.
 */
class ReplaceCheck(
    private val show: (ServerReplacement?) -> Unit,
    private val waitMillis: Long = CertificateCheck.DEFAULT_WAIT_MILLIS,
) {
    private var pending: CompletableDeferred<Boolean>? = null

    /** Records the user's answer. Does nothing while no replacement waits. */
    fun answer(replace: Boolean) {
        pending?.complete(replace)
    }

    /** Shows [change] and returns whether the user wants it. */
    suspend fun await(change: ServerReplacement): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pending = decision
        show(change)
        try {
            return withTimeoutOrNull(waitMillis) { decision.await() } ?: false
        } finally {
            pending = null
            show(null)
        }
    }

    /**
     * Returns normally when [scanned] may be used: it names the stored server, nothing is stored, or the user agreed.
     * Throws a [SubsonicException] that the connect flow shows as an error when the user kept the current connection.
     */
    suspend fun confirm(
        stored: ServerSettings?,
        scanned: HttpUrl,
    ) {
        val change = replacementOf(stored, scanned) ?: return
        if (!await(change)) throw SubsonicException(null, REPLACE_DECLINED)
    }
}

/** Asks whether the scanned connection replaces the current one. Back and a tap outside keep the current one. */
@Composable
internal fun ReplaceConfirmation(
    change: ServerReplacement?,
    onAnswer: (Boolean) -> Unit,
) {
    if (change == null) return
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Replace the connection?") },
        text = {
            Text(
                "This code connects to ${change.newHost}. It replaces the current connection to " +
                    "${change.currentHost}, and the library synced from it is removed from this phone.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Keep current") } },
    )
}
