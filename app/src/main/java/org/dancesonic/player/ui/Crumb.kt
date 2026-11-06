package org.dancesonic.player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One level of the path shown above a list. [current] is the level on screen, which is not tappable; the others
 * jump straight to their level.
 */
internal data class Crumb(
    val label: String,
    val current: Boolean,
    val onClick: () -> Unit = {},
)

/** The path to the level on screen, such as Library › artist › release. */
@Composable
internal fun Breadcrumbs(crumbs: List<Crumb>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text(" › ", style = MaterialTheme.typography.bodyMedium)
            if (crumb.current) {
                Text(crumb.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            } else {
                TextButton(onClick = crumb.onClick) { Text(crumb.label) }
            }
        }
    }
}
