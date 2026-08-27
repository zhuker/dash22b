package com.example.dash22b.data.history

import com.example.dash22b.data.DisplayUnit
import com.example.dash22b.data.EngineData
import com.example.dash22b.data.ParameterDefinition
import com.example.dash22b.data.ValueWithUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesConvertTest {

    private class Def(name: String, unit: DisplayUnit) :
        ParameterDefinition(name, "float", unit, name, name, 0f, 100f, name)

    private fun store(param: String, unit: DisplayUnit, vararg pts: Pair<Long, Float>): HistoryStore {
        val s = HistoryStore(capacity = 64)
        pts.forEach { (ts, v) ->
            s.record(EngineData(timestamp = ts, values = mapOf(param to ValueWithUnit(v, unit))))
        }
        return s
    }

    @Test
    fun `kPa converts to psi`() {
        val s = store("Boost", DisplayUnit.KPA, 0L to 52f)
        val raw = SeriesBuffer(1)
        val out = SeriesBuffer(1)
        s.query("Boost", 0L, 100L, raw)

        convertInto(raw, out, Def("Boost", DisplayUnit.KPA), DisplayUnit.PSI)

        assertEquals(7.54f, out.mean[0], 0.01f)
        assertEquals(DisplayUnit.PSI, out.unit)
    }

    @Test
    fun `source buffer is left untouched so a later toggle reconverts from raw`() {
        val s = store("Boost", DisplayUnit.KPA, 0L to 100f)
        val raw = SeriesBuffer(1)
        val out = SeriesBuffer(1)
        s.query("Boost", 0L, 100L, raw)

        convertInto(raw, out, Def("Boost", DisplayUnit.KPA), DisplayUnit.PSI)
        assertEquals(100f, raw.mean[0], 0f)

        // Convert the same raw buffer again, to a different unit.
        convertInto(raw, out, Def("Boost", DisplayUnit.KPA), DisplayUnit.BAR)
        assertEquals(1f, out.mean[0], 0.001f)
    }

    @Test
    fun `decreasing calibration keeps min below max`() {
        // Fuel sender: higher voltage means more fuel, so GALLONS_TO_FILL is inverted.
        val s = store("Fuel Level", DisplayUnit.VOLTS, 0L to 1f, 10L to 4f)
        val raw = SeriesBuffer(1)
        val out = SeriesBuffer(1)
        s.query("Fuel Level", 0L, 100L, raw)
        assertTrue(raw.min[0] < raw.max[0])

        convertInto(raw, out, Def("Fuel Level", DisplayUnit.VOLTS), DisplayUnit.GALLONS_TO_FILL)

        assertTrue(
            "min must stay <= max after an inverting conversion",
            out.min[0] <= out.max[0]
        )
    }

    @Test
    fun `NaN gaps survive conversion`() {
        val s = store("Boost", DisplayUnit.KPA, 0L to 10f, 90L to 20f)
        val raw = SeriesBuffer(10)
        val out = SeriesBuffer(10)
        s.query("Boost", 0L, 100L, raw)

        convertInto(raw, out, Def("Boost", DisplayUnit.KPA), DisplayUnit.PSI)

        assertTrue(out.mean[5].isNaN())
        assertTrue(out.min[5].isNaN())
    }

    @Test
    fun `same unit copies through unchanged`() {
        val s = store("Boost", DisplayUnit.KPA, 0L to 52f)
        val raw = SeriesBuffer(1)
        val out = SeriesBuffer(1)
        s.query("Boost", 0L, 100L, raw)

        convertInto(raw, out, Def("Boost", DisplayUnit.KPA), DisplayUnit.KPA)

        assertEquals(52f, out.mean[0], 0f)
    }
}
