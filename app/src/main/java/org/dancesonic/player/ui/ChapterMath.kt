package org.dancesonic.player.ui

import org.dancesonic.player.data.db.ChapterEntity

/** How far a file may differ from DanceBrainz's length before its chapter times are shown as approximate. */
internal const val LENGTH_TOLERANCE_MS = 3_000L

/** Within this many milliseconds of a chapter's start, "previous" goes to the start of the current chapter. */
internal const val RESTART_THRESHOLD_MS = 3_000L

/** The name shown for a chapter: the title, else the dance, else "Chapter n"; "title (dance)" when both are set. */
internal fun chapterName(
    chapter: ChapterEntity,
    number: Int,
): String {
    val title = chapter.title?.takeIf { it.isNotBlank() }
    val dance = chapter.danceName?.takeIf { it.isNotBlank() }
    return when {
        title != null && dance != null -> "$title ($dance)"
        title != null -> title
        dance != null -> dance
        else -> "Chapter $number"
    }
}

/**
 * The index of the chapter playing at [positionMs]: the last one that has started. -1 before the first one starts,
 * and when there are none. [chapters] must be sorted by start.
 */
internal fun currentChapterIndex(
    chapters: List<ChapterEntity>,
    positionMs: Long,
): Int = chapters.indexOfLast { it.startMs <= positionMs }

/**
 * Where "previous chapter" seeks. Near the start of the current chapter it goes to the one before; further in, it
 * goes back to the start of the current one. Null when there is no chapter to go to.
 */
internal fun previousChapterStart(
    chapters: List<ChapterEntity>,
    positionMs: Long,
): Long? {
    val index = currentChapterIndex(chapters, positionMs)
    if (index < 0) return null
    val current = chapters[index]
    return when {
        positionMs - current.startMs > RESTART_THRESHOLD_MS -> current.startMs
        index > 0 -> chapters[index - 1].startMs
        else -> null
    }
}

/** Where "next chapter" seeks: the first chapter that starts after [positionMs]. Null at the last chapter. */
internal fun nextChapterStart(
    chapters: List<ChapterEntity>,
    positionMs: Long,
): Long? = chapters.firstOrNull { it.startMs > positionMs }?.startMs

/**
 * The length DanceBrainz gives the recording: the last chapter's end, which is the recording's length when it is
 * known. Null when there are no chapters or the length is not known.
 */
internal fun knownLengthMs(chapters: List<ChapterEntity>): Long? = chapters.lastOrNull()?.endMs

/**
 * True when the file being played may not match the recording the chapters belong to: its length differs from the
 * recording's by more than [LENGTH_TOLERANCE_MS]. Such chapters are shown as approximate and are not changed.
 */
internal fun chaptersApproximate(
    chapters: List<ChapterEntity>,
    fileDurationMs: Long,
): Boolean {
    val length = knownLengthMs(chapters) ?: return false
    if (fileDurationMs <= 0L) return false
    return kotlin.math.abs(fileDurationMs - length) > LENGTH_TOLERANCE_MS
}
