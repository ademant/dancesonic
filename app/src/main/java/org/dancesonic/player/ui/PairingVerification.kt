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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dancesonic.player.account.PairingDecision

/**
 * The verification code of a pairing (folksonic #103) with the two answers. Nothing is shown when [code] is null.
 * The code is shown large so that it can be compared with the account page at a glance.
 */
@Composable
internal fun PairingVerification(
    code: String?,
    onDecision: (PairingDecision) -> Unit,
) {
    if (code == null) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Verification code", style = MaterialTheme.typography.titleSmall)
        Text(code, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDecision(PairingDecision.MATCH) }) { Text("Code matches") }
            OutlinedButton(onClick = { onDecision(PairingDecision.CANCEL) }) { Text("Cancel") }
        }
    }
}
