package org.dancesonic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/** The section of names that do not start with a letter. It is listed last. */
internal const val OTHER_SECTION = "#"

/** The names in one section, in their original order. [letter] is `A` to `Z`, or [OTHER_SECTION]. */
internal data class LetterGroup<T>(
    val letter: String,
    val items: List<T>,
)

/**
 * The section of [name]: its first letter with accents removed, in upper case, so `Émile` is under `E`. A name whose
 * first character is not a basic Latin letter goes under [OTHER_SECTION].
 */
internal fun sectionOf(name: String): String {
    val withoutAccents = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    val first = withoutAccents.firstOrNull()?.uppercaseChar()
    return if (first != null && first in 'A'..'Z') first.toString() else OTHER_SECTION
}

/**
 * Splits [items] into one group per section, in alphabetical order with [OTHER_SECTION] last. Items keep their order
 * within a group.
 */
internal fun <T> groupByFirstLetter(
    items: List<T>,
    nameOf: (T) -> String,
): List<LetterGroup<T>> =
    items
        .groupBy { sectionOf(nameOf(it)) }
        .map { (letter, members) -> LetterGroup(letter, members) }
        .sortedWith(compareBy({ it.letter == OTHER_SECTION }, { it.letter }))

/**
 * Which letter sections of one list are open. Kept while the app runs, so the list comes back with its sections as they
 * were left. Every section starts closed.
 */
class LetterSections {
    private val open = mutableStateMapOf<String, Boolean>()

    fun isOpen(letter: String): Boolean = open[letter] == true

    fun toggle(letter: String) {
        open[letter] = open[letter] != true
    }
}

/**
 * A list in letter sections. Each section is a header with its letter and count; the rows of a section show only when
 * it is open. [row] draws one item.
 */
@Composable
internal fun <T> LetterGroupedList(
    groups: List<LetterGroup<T>>,
    listState: LazyListState,
    sections: LetterSections,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
        groups.forEach { group ->
            val open = sections.isOpen(group.letter)
            item(key = "section:${group.letter}") {
                LetterHeader(group.letter, group.items.size, open, onClick = { sections.toggle(group.letter) })
            }
            if (open) {
                items(group.items, key = key) { item -> row(item) }
            }
        }
    }
}

@Composable
private fun LetterHeader(
    letter: String,
    count: Int,
    open: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$letter · $count",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider()
    }
}

/** Combining marks, which NFD splits off accented letters. */
private val COMBINING_MARKS = Regex("\\p{Mn}")
