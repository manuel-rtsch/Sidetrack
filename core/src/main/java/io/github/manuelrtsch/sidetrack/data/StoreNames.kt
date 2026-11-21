package io.github.manuelrtsch.sidetrack.data

/**
 * The per-service file names a runtime keeps its state in.
 *
 * Settings live in their own store; queue, history and the resume point are files under the app's
 * files dir. Naming them in one value keeps two accounts of the same service from sharing a file,
 * and keeps the literals out of every runtime.
 */
data class StoreNames(
    val settings: String,
    val queue: String,
    val history: String,
    val resume: String
) {
    companion object {
        /** The names a plain provider uses: `sidetracks_settings_<key>` plus the three `.bin` files. */
        fun forService(key: String): StoreNames = StoreNames(
            settings = "sidetracks_settings_$key",
            queue = "sidetracks-$key-queue.bin",
            history = "sidetracks-$key-history.bin",
            resume = "sidetracks-$key-resume.bin"
        )
    }
}
