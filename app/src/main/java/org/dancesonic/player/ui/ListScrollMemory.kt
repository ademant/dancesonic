package org.dancesonic.player.ui

/**
 * Where each list was scrolled to, by list key, for as long as the app runs. Going back to a list returns to the
 * place it was left; it is not kept across an app restart.
 */
class ListScrollMemory {
    private val positions = HashMap<String, Pair<Int, Int>>()

    /** The first visible item and its offset when the list [key] was last scrolled; (0, 0) when never. */
    fun position(key: String): Pair<Int, Int> = positions[key] ?: (0 to 0)

    /** Records where the list [key] is scrolled to. */
    fun remember(
        key: String,
        firstVisibleItem: Int,
        offset: Int,
    ) {
        positions[key] = firstVisibleItem to offset
    }
}
