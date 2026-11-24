package io.github.manuelrtsch.sidetrack.playback

/** "3:07", "1:02:45". */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * A list row's duration: "3:07" and "75:30" below 99 minutes, then "1h 40m". Two-digit minute
 * counts stay exact at list glance length; only the longer recordings trade seconds for the
 * shorter hour form, so the trailing column does not widen on those rows.
 */
fun formatListDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    if (total < 99 * 60) return "%d:%02d".format(total / 60, total % 60)
    val h = total / 3600
    val m = (total % 3600) / 60
    return "%dh %02dm".format(h, m)
}
