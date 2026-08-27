package com.example.dash22b.data.history

import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.EngineData
import com.example.dash22b.data.ValueWithUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {

    private fun sample(ts: Long, vararg values: Pair<String, Float>) = EngineData(
        timestamp = ts,
        values = values.associate { (name, v) -> name to ValueWithUnit(v, DisplayUnit.KPA) }
    )

    @Test
    fun `records and reads back a single sample`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(1_000L, "Boost" to 52f))

        val out = SeriesBuffer(4)
        store.query("Boost", 1_000L, 2_000L, out)

        assertEquals(4, out.count)
        assertEquals(DisplayUnit.KPA, out.unit)
        assertEquals(52f, out.min[0], 0f)
        assertEquals(52f, out.max[0], 0f)
        assertEquals(52f, out.mean[0], 0f)
    }

    @Test
    fun `bucket keeps min and max, not just the average`() {
        val store = HistoryStore(capacity = 16)
        // Three samples inside one bucket, with a spike in the middle.
        store.record(sample(0L, "Boost" to 10f))
        store.record(sample(10L, "Boost" to 200f))
        store.record(sample(20L, "Boost" to 12f))

        val out = SeriesBuffer(1)
        store.query("Boost", 0L, 100L, out)

        assertEquals(10f, out.min[0], 0f)
        assertEquals(200f, out.max[0], 0f)
        assertEquals(74f, out.mean[0], 0.01f)
    }

    @Test
    fun `empty buckets are NaN gaps`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(0L, "Boost" to 10f))
        store.record(sample(90L, "Boost" to 20f))

        val out = SeriesBuffer(10)
        store.query("Boost", 0L, 100L, out)

        assertEquals(10f, out.mean[0], 0f)
        assertTrue("middle buckets should be gaps", out.mean[5].isNaN())
        assertEquals(20f, out.mean[9], 0f)
    }

    @Test
    fun `ring wraps and keeps only the newest samples`() {
        val store = HistoryStore(capacity = 4)
        repeat(10) { i -> store.record(sample(i * 100L, "Boost" to i.toFloat())) }

        assertEquals(4, store.size)
        assertEquals(600L..900L, store.span())

        val out = SeriesBuffer(4)
        store.query("Boost", 600L, 900L, out)
        assertEquals(6f, out.mean[0], 0f)
        assertEquals(9f, out.mean[3], 0f)
    }

    @Test
    fun `parameter appearing mid-drive back-fills gaps`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(0L, "Boost" to 10f))
        store.record(sample(100L, "Boost" to 11f, "Coolant Temp" to 80f))

        val out = SeriesBuffer(2)
        store.query("Coolant Temp", 0L, 200L, out)

        assertTrue("no data before the parameter appeared", out.mean[0].isNaN())
        assertEquals(80f, out.mean[1], 0f)
    }

    @Test
    fun `parameter dropping out leaves a gap, not a held value`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(0L, "Boost" to 10f, "Coolant Temp" to 80f))
        store.record(sample(100L, "Boost" to 11f))

        val out = SeriesBuffer(2)
        store.query("Coolant Temp", 0L, 200L, out)

        assertEquals(80f, out.mean[0], 0f)
        assertTrue("dropped parameter must not hold its last value", out.mean[1].isNaN())
    }

    @Test
    fun `queries outside the retained window return nothing`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(1_000L, "Boost" to 52f))

        val out = SeriesBuffer(4)
        store.query("Boost", 5_000L, 6_000L, out)
        assertEquals(0, out.count)
        assertTrue(out.isEmpty)

        store.query("Boost", 0L, 500L, out)
        assertEquals(0, out.count)
    }

    @Test
    fun `unknown parameter and empty store are handled`() {
        val store = HistoryStore(capacity = 16)
        val out = SeriesBuffer(4)

        store.query("Nope", 0L, 100L, out)
        assertEquals(0, out.count)
        assertNull(store.span())

        store.record(sample(0L, "Boost" to 1f))
        store.query("Nope", 0L, 100L, out)
        assertEquals(0, out.count)
    }

    @Test
    fun `buckets exceeding sample count leave most of the grid empty`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(0L, "Boost" to 10f))
        store.record(sample(1_000L, "Boost" to 20f))

        val out = SeriesBuffer(100)
        store.query("Boost", 0L, 1_000L, out)

        val filled = (0 until out.count).count { !out.mean[it].isNaN() }
        assertEquals(2, filled)
    }

    @Test
    fun `inverted or empty time range yields nothing`() {
        val store = HistoryStore(capacity = 16)
        store.record(sample(500L, "Boost" to 10f))

        val out = SeriesBuffer(4)
        store.query("Boost", 1_000L, 1_000L, out)
        assertEquals(0, out.count)

        store.query("Boost", 1_000L, 500L, out)
        assertEquals(0, out.count)
    }

    @Test
    fun `queryLatest follows the newest sample`() {
        val store = HistoryStore(capacity = 100)
        repeat(20) { i -> store.record(sample(i * 1_000L, "Boost" to i.toFloat())) }

        val out = SeriesBuffer(5)
        store.queryLatest("Boost", 5_000L, out)

        assertEquals(14_000L, out.fromTs)
        assertEquals(19_000L, out.toTs)
        // The window's inclusive right edge clamps into the last bucket, so it holds
        // both 18 and 19; the newest value shows up as that bucket's max.
        assertEquals(19f, out.max[out.count - 1], 0f)
        assertEquals(18.5f, out.mean[out.count - 1], 0f)
    }

    @Test
    fun `version advances once per sample`() {
        val store = HistoryStore(capacity = 8)
        assertEquals(0L, store.version.value)
        store.record(sample(0L, "Boost" to 1f))
        store.record(sample(1L, "Boost" to 2f))
        assertEquals(2L, store.version.value)

        // Rows with no values are ignored entirely.
        store.record(EngineData(timestamp = 2L))
        assertEquals(2L, store.version.value)
    }
}
