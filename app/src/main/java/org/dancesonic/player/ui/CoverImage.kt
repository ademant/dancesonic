package org.dancesonic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A square cover image. Until the image is loaded, or when there is no URL, the space shows as a plain block so the
 * layout does not move.
 */
@Composable
internal fun CoverImage(
    url: String?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(COVER_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Pixels asked for from the server for the large cover in the track header. */
internal const val COVER_PX_LARGE = 512

/** Pixels asked for from the server for the small cover in the player bar. */
internal const val COVER_PX_SMALL = 128

private val COVER_CORNER = 6.dp
