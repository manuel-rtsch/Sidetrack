package io.github.manuelrtsch.sidetrack

// Timing constants for the playback path.
//
// They live in their own file so the seek/resume behaviour can be read and tuned without paging
// through the ViewModel. They are internal to the module rather than private to a file so the
// playback collaborators can share them.

/** How long the now-playing confirmation owns the action bar before the normal actions return. */
const val NOW_PLAYING_NOTICE_MS = 4_000L

/**
 * How long the bar offers to put back a queue a collection tap replaced. Longer than the
 * now-playing confirmation: reading "Queue replaced" and deciding to undo it is a decision, not a
 * glance, and the tap that caused it did not announce itself the way a row action does.
 */
internal const val QUEUE_REPLACED_UNDO_MS = 8_000L

/** The podcast transport's rewind and fast-forward step. */
internal const val PODCAST_SKIP_MS = 30_000L

/** How often a playing episode writes its local resume point. */
internal const val EPISODE_PROGRESS_INTERVAL_MS = 5_000L

/**
 * How often the seek bar advances while it is on screen. The bar animates between samples, so a
 * one-second step still reads as continuous while waking the main loop once a second instead of
 * five times.
 */
internal const val POSITION_TICK_MS = 1_000L

/**
 * A native position event this far from the UI clock is treated as a real seek from another
 * client. Smaller differences are the decoder's normal lead over the speaker, and adopting them
 * would make the bar jump forward every time an event arrives.
 */
internal const val POSITION_EVENT_JUMP_MS = 5_000L

/**
 * Upper bound on how long the seek bar waits for the native player to confirm a new item. The
 * hold normally ends with the loading/playing event; this only keeps a lost event from freezing
 * the bar forever.
 */
internal const val PLAYBACK_START_TIMEOUT_MS = 10_000L

/** How long a resumed track ignores player events from the generation that was stopped. */
internal const val RESUME_GRACE_MS = 8_000L

/** An event this far below the resumed offset is the old player state, not a real seek. */
internal const val RESUME_EVENT_SLACK_MS = 2_000L

/** How long a local seek owns the bar while waiting for the player to report the new offset. */
internal const val SEEK_CONFIRM_TIMEOUT_MS = 15_000L
