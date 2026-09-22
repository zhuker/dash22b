package com.example.dash22b.data.history

import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.EngineData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fixed-capacity columnar ring of monitored ECU values.
 *
 * Storage is one FloatArray per parameter plus one shared LongArray of timestamps,
 * so a sample costs 8 + 4*paramCount bytes instead of the ~600 a Map-backed
 * EngineData row costs. Capacity is counted in samples, so the time it covers
 * depends on the poll rate, which with fast poll (SSM continuous read) depends on
 * how many parameters the preset reads: ~22 Hz for 13, up to ~43 Hz for one 4-byte
 * parameter, ~6 Hz in the single-read fallback. 36_000 samples is ~20 minutes at
 * 30 Hz, ~27 at the usual 22 Hz, and ~1.7 MB for ten parameters.
 *
 * Values are stored in their SSM source unit. Display-unit conversion happens at
 * draw time, so changing a gauge's unit reconverts the whole retained series
 * instead of leaving a seam in the buffer.
 *
 * Single producer (the service polling loop), many readers (graph composables).
 * Both sides take the monitor. Every recorded sample bumps [version] and each
 * visible graph re-queries, so at fast-poll rates a graph on the "All" window scans
 * the whole ring ~20 times a second while holding the lock the recorder needs. Left
 * unthrottled on purpose for now; see docs/ssm_fastpoll_plan.md.
 */
class HistoryStore(val capacity: Int = DEFAULT_CAPACITY) {

    private class Column(val unit: DisplayUnit, val values: FloatArray)

    private val lock = Any()
    private val columns = LinkedHashMap<String, Column>()
    private val timestamps = LongArray(capacity)

    /** Total samples ever recorded. The ring holds the last min(recorded, capacity). */
    private var recorded: Long = 0L

    private val _version = MutableStateFlow(0L)

    /** Bumped once per recorded sample. Graphs observe this to know to redraw. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Samples currently retained. */
    val size: Int
        get() = synchronized(lock) { retainedLocked() }

    /** Appends one complete ECU response. Service thread only. */
    fun record(data: EngineData) {
        if (data.values.isEmpty()) return
        synchronized(lock) {
            val slot = (recorded % capacity).toInt()

            data.values.forEach { (name, value) ->
                val column = columns.getOrPut(name) {
                    // A parameter can appear mid-drive when the preset changes. Back-fill
                    // NaN so its history starts as a gap rather than a run of zeroes.
                    Column(value.unit, FloatArray(capacity) { Float.NaN })
                }
                column.values[slot] = value.value
            }

            // Columns absent from this row get a gap, so a parameter that drops out of
            // the preset stops drawing instead of holding its last value forever.
            columns.forEach { (name, column) ->
                if (!data.values.containsKey(name)) column.values[slot] = Float.NaN
            }

            timestamps[slot] = data.timestamp
            recorded++
        }
        _version.value = recorded
    }

    /** Oldest..newest retained timestamp, or null when empty. */
    fun span(): LongRange? = synchronized(lock) {
        val n = retainedLocked()
        if (n == 0) return null
        timestamps[physical(0, n)]..timestamps[physical(n - 1, n)]
    }

    /** Parameters with at least one retained sample, in first-seen order. */
    fun params(): Set<String> = synchronized(lock) { LinkedHashSet(columns.keys) }

    /** Source unit a column is stored in, or null if the parameter is unknown. */
    fun unitOf(param: String): DisplayUnit? = synchronized(lock) { columns[param]?.unit }

    /**
     * Fills [out] with out.buckets buckets spanning [fromTs, toTs].
     *
     * Each bucket carries min/max/mean rather than a single value: averaging alone
     * would hide a knock spike or boost overshoot once the window is wider than the
     * bucket count. Empty buckets are NaN so the renderer breaks the line across
     * gaps instead of interpolating through them.
     */
    fun query(param: String, fromTs: Long, toTs: Long, out: SeriesBuffer) {
        val buckets = out.buckets
        out.reset(param, DisplayUnit.UNKNOWN, fromTs, toTs)
        if (toTs <= fromTs) return

        synchronized(lock) {
            val column = columns[param] ?: return
            out.unit = column.unit

            val n = retainedLocked()
            if (n == 0) return

            val span = (toTs - fromTs).toDouble()
            var i = lowerBoundLocked(fromTs, n)
            while (i < n) {
                val ts = timestamps[physical(i, n)]
                if (ts > toTs) break
                val v = column.values[physical(i, n)]
                if (!v.isNaN()) {
                    val b = (((ts - fromTs) / span) * buckets).toInt().coerceIn(0, buckets - 1)
                    out.accumulate(b, v)
                }
                i++
            }
        }

        out.finish()
    }

    /**
     * Everything retained, oldest sample to newest.
     *
     * Note this is the ring's contents, not the whole drive: once [capacity] samples
     * have been recorded the oldest are overwritten, so "all" means "all we still have".
     */
    fun queryAll(param: String, out: SeriesBuffer) {
        val span = span()
        if (span == null || span.first == span.last) {
            out.reset(param, unitOf(param) ?: DisplayUnit.UNKNOWN, span?.first ?: 0L, span?.last ?: 0L)
            return
        }
        query(param, span.first, span.last, out)
    }

    /** Live-follow convenience: the trailing [durationMs] ending at the newest sample. */
    fun queryLatest(param: String, durationMs: Long, out: SeriesBuffer) {
        val newest = span()?.last
        if (newest == null) {
            out.reset(param, DisplayUnit.UNKNOWN, 0L, 0L)
            return
        }
        query(param, newest - durationMs, newest, out)
    }

    /** Drops all retained samples and columns. */
    fun clear() {
        synchronized(lock) {
            columns.clear()
            recorded = 0L
        }
        _version.value = 0L
    }

    private fun retainedLocked(): Int =
        if (recorded < capacity) recorded.toInt() else capacity

    /** Logical index [i] (0 = oldest retained) to its slot in the ring. */
    private fun physical(i: Int, n: Int): Int =
        (((recorded - n) + i) % capacity).toInt()

    /** First logical index whose timestamp is >= [ts]; [n] if none is. */
    private fun lowerBoundLocked(ts: Long, n: Int): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestamps[physical(mid, n)] < ts) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** ~20 minutes at 30 Hz, ~27 at the ~22 Hz fast poll gives a 13-parameter preset. */
        const val DEFAULT_CAPACITY = 36_000
    }
}
