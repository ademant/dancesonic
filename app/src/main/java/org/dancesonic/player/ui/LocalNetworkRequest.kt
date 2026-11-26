package org.dancesonic.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.account.ConnectionUri
import org.dancesonic.player.account.PairingUri
import org.dancesonic.player.subsonic.LocalNetworkAccess
import org.dancesonic.player.subsonic.ServerUrl

/**
 * Asks for Android 17's local network permission (#124). [ensure] shows the system prompt when the permission is
 * missing and returns once the user answered; it returns at once when the permission is granted or not needed.
 */
internal class LocalNetworkRequest(
    private val isGranted: () -> Boolean,
) {
    private var waiting: CompletableDeferred<Unit>? = null

    /** Shows the system prompt for these permissions. Set by the screen that owns the prompt. */
    var launch: (Array<String>) -> Unit = {}

    /** Shows the prompt if needed and waits for the answer. A denial is not an error here: the connect reports it. */
    suspend fun ensure() {
        if (isGranted()) return
        val answer = CompletableDeferred<Unit>()
        waiting = answer
        launch(LocalNetworkAccess.required())
        answer.await()
    }

    /** Called with the prompt's result. */
    fun answered() {
        waiting?.complete(Unit)
        waiting = null
    }

    /** [ensure], only when [server] is on the local network. Public servers never need the permission. */
    suspend fun ensureFor(server: HttpUrl?) {
        if (server != null && LocalNetworkAccess.needsPermission(server)) ensure()
    }
}

/** A [LocalNetworkRequest] tied to this screen's permission prompt. */
@Composable
internal fun rememberLocalNetworkRequest(): LocalNetworkRequest {
    val context = LocalContext.current.applicationContext
    val request = remember { LocalNetworkRequest(isGranted = { LocalNetworkAccess.granted(context) }) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { request.answered() }
    request.launch = { launcher.launch(it) }
    return request
}

/** The server a typed address names, or null when it is not a valid address. Nothing is resolved. */
internal fun typedServer(
    address: String,
    allowCleartext: Boolean,
): HttpUrl? = runCatching { ServerUrl.parse(address, allowCleartext) }.getOrNull()

/** The server a scanned connection or pairing code names, or null when it names none. Nothing is resolved. */
internal fun scannedServer(
    rawCode: String,
    allowCleartext: Boolean,
): HttpUrl? =
    runCatching {
        if (ConnectionUri.isConnectionCode(rawCode)) {
            ConnectionUri.parse(rawCode, allowCleartext).server.baseUrl
        } else {
            PairingUri.parse(rawCode, allowCleartext).server
        }
    }.getOrNull()

/**
 * A button that asks for the local network permission again, shown after a connect failed without it (#124). Android
 * stops showing the prompt after repeated denials; the message then points to the system settings.
 */
@Composable
internal fun LocalNetworkAllow(
    blocked: Boolean,
    request: LocalNetworkRequest,
) {
    if (!blocked) return
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = { scope.launch { request.ensure() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Allow local network access")
    }
    Text(
        "If no prompt appears, allow \"Nearby devices\" for DanceSonic in the system settings, then connect again.",
        style = MaterialTheme.typography.bodySmall,
    )
}
