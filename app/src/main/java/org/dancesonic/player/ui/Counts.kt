package org.dancesonic.player.ui

/**
 * [n] with the noun that fits it, such as `1 track` or `12 tracks`. [plural] defaults to [singular] with an s, which
 * covers every noun the app counts.
 */
internal fun count(
    n: Int,
    singular: String,
    plural: String = singular + "s",
): String = "$n ${if (n == 1) singular else plural}"
