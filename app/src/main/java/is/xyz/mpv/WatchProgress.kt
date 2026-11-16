package `is`.xyz.mpv

import android.content.Context
import kotlin.math.abs

/**
 * Where playback stopped in each local file, so the TV library can draw a progress bar on the tile.
 *
 * Values live in their own preferences file, keyed by the file's path (the same string the player
 * is given and mpv reports back as `path`). Each value is "position|duration|savedAt", in seconds,
 * seconds and milliseconds. Files that are finished, barely started or very short have no entry.
 */
object WatchProgress {
    private const val PREFS = "tv_watch_progress"
    private const val MAX_ENTRIES = 500

    /** Shorter files are never tracked. */
    private const val MIN_DURATION_SEC = 120L
    /** Stopping before this counts as not started. */
    private const val MIN_POSITION_SEC = 10L
    /** Past this fraction, or within the last [DONE_REMAINING_SEC], a file counts as finished. */
    private const val DONE_FRACTION = 0.95
    private const val DONE_REMAINING_SEC = 15L
    /** While playing, the position is written again after this many seconds. */
    internal const val AUTOSAVE_SEC = 15L

    data class Entry(val positionSec: Long, val durationSec: Long) {
        /** Whole percent watched, 1 to 99, for the bar. */
        val percent: Int
            get() = (positionSec * 100 / durationSec).toInt().coerceIn(1, 99)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parse(raw: Any?): Triple<Long, Long, Long>? {
        val parts = (raw as? String)?.split('|') ?: return null
        if (parts.size < 2)
            return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        if (pos < 0 || dur <= 0)
            return null
        return Triple(pos, dur, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /** Every saved position, by file path. */
    fun readAll(ctx: Context): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        for ((path, raw) in prefs(ctx).all) {
            val (pos, dur, _) = parse(raw) ?: continue
            out[path] = Entry(pos, dur)
        }
        return out
    }

    /** Saves the position of [path], or forgets it if the file is finished or barely started. */
    fun record(ctx: Context, path: String, positionSec: Long, durationSec: Long) {
        // only real files can be matched with a tile; content and fd URIs never can
        if (!path.startsWith("/") || durationSec < MIN_DURATION_SEC)
            return
        val prefs = prefs(ctx)
        val finished = positionSec.toDouble() >= durationSec * DONE_FRACTION ||
            durationSec - positionSec < DONE_REMAINING_SEC
        if (positionSec < MIN_POSITION_SEC || finished) {
            if (prefs.contains(path))
                prefs.edit().remove(path).apply()
            return
        }
        val edit = prefs.edit()
        val all = prefs.all
        if (all.size >= MAX_ENTRIES && !all.containsKey(path)) {
            // keep the most recent ones
            all.entries
                .sortedBy { parse(it.value)?.third ?: 0L }
                .take(all.size - MAX_ENTRIES + 1)
                .forEach { edit.remove(it.key) }
        }
        edit.putString(path, "$positionSec|$durationSec|${System.currentTimeMillis()}").apply()
    }

    /**
     * Follows the file being played. Fed from mpv's property events (event thread) and flushed
     * from the activity (UI thread), so every method locks.
     */
    class Tracker(context: Context) {
        private val appContext = context.applicationContext

        /** Off when "Save position on quit" is off. */
        @Volatile
        var enabled = false

        private var path = ""
        private var position = 0L
        private var duration = 0L
        private var lastSaved = 0L

        /** The player moved on to [newPath]: store how the last file ended, then start over. */
        @Synchronized
        fun onPath(newPath: String) {
            save()
            path = newPath
            position = 0L
            duration = 0L
            lastSaved = 0L
        }

        @Synchronized
        fun onPosition(sec: Long) {
            position = sec
            // a crash or a power cut should not lose the whole session
            if (abs(sec - lastSaved) >= WatchProgress.AUTOSAVE_SEC)
                save()
        }

        @Synchronized
        fun onDuration(sec: Double) {
            if (sec.isFinite() && sec > 0)
                duration = sec.toLong()
        }

        /** Writes the current position without forgetting the file. */
        @Synchronized
        fun save() {
            if (!enabled || path.isEmpty() || duration <= 0)
                return
            lastSaved = position
            WatchProgress.record(appContext, path, position, duration)
        }
    }
}
