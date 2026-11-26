package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.LanPins
import org.dancesonic.player.subsonic.LanTls
import org.dancesonic.player.subsonic.SubsonicException

/** Shown when a pairing code's pin and the certificate the server presents differ. */
internal const val PIN_MISMATCH =
    "The server certificate does not match the pin in this code. Create a new code on the account page."

/** Shown when the user did not trust the certificate. */
internal const val CERTIFICATE_NOT_TRUSTED = "The server certificate was not trusted. Nothing was sent to the server."

/** Shown when nobody answered in time. */
internal const val CERTIFICATE_CHECK_EXPIRED = "The certificate was not confirmed in time. Connect again."

/**
 * The user's check of a private server's self-signed certificate (#126). [await] shows the fingerprint through [show]
 * and waits for the answer that [answer] records. [show] receives null once nothing is to be shown.
 */
class CertificateCheck(
    private val show: (String?) -> Unit,
    private val waitMillis: Long = DEFAULT_WAIT_MILLIS,
) {
    private var pending: CompletableDeferred<Boolean>? = null

    /** Records the user's answer. Does nothing while no fingerprint waits. */
    fun answer(trusted: Boolean) {
        pending?.complete(trusted)
    }

    /** Shows [fingerprint] and returns whether the user trusts it. False when no answer comes in time. */
    suspend fun await(fingerprint: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pending = decision
        show(fingerprint)
        try {
            return withTimeoutOrNull(waitMillis) { decision.await() } ?: throw SubsonicException(
                null,
                CERTIFICATE_CHECK_EXPIRED,
            )
        } finally {
            pending = null
            show(null)
        }
    }

    companion object {
        const val DEFAULT_WAIT_MILLIS = 5L * 60_000L
    }
}

/**
 * Reads [url]'s certificate and decides whether to trust it, before any request carries a key, password, session or
 * pairing token (#126). [expected] is the pin a pairing code carries: the certificate must match it. Without one, a
 * certificate the stored connection already pins is trusted, and any other is shown to the user through [confirm].
 * A trusted pin is active only for this setup ([LanPins.trustForSetup]); a refused one never is. Returns the pin to
 * store, or null for a server that is not pinned.
 */
internal suspend fun trustedPin(
    url: HttpUrl,
    expected: String?,
    confirm: suspend (String) -> Boolean,
    read: suspend (HttpUrl) -> String? = { LanTls.connectPin(it) },
): String? {
    val observed = read(url) ?: return null
    val trusted =
        when {
            expected != null -> if (expected == observed) true else throw SubsonicException(null, PIN_MISMATCH)
            observed == LanPins.savedPinFor(url) -> true
            else -> confirm(observed)
        }
    if (!trusted) throw SubsonicException(null, CERTIFICATE_NOT_TRUSTED)
    LanPins.trustForSetup(url, observed)
    return observed
}

/**
 * The fingerprint of a self-signed server certificate, to compare with the server before anything secret is sent.
 * Nothing is shown when [fingerprint] is null.
 */
@Composable
internal fun CertificateConfirmation(
    fingerprint: String?,
    onAnswer: (Boolean) -> Unit,
) {
    if (fingerprint == null) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Check the server certificate", style = MaterialTheme.typography.titleSmall)
        Text(
            "This server on your local network uses its own certificate. Compare its fingerprint with the one the " +
                "server shows (for example `tinysonic cert fingerprint`). Trust it only if they are the same.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(fingerprint, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }) { Text("Trust") }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Cancel") }
        }
    }
}
