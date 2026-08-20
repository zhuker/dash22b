package com.example.dash22b.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitorCsvWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startsNewWideCsvWhenMonitoredColumnsChange() = runBlocking {
        val directory = temporaryFolder.newFolder("monitor")
        val writer = MonitorCsvWriter(directory)

        writer.record(
            EngineData(
                timestamp = 1_700_000_000_000L,
                values = linkedMapOf(
                    "Engine Speed" to ValueWithUnit(4507f, DisplayUnit.RPM),
                    "Manifold Relative Pressure" to ValueWithUnit(64f, DisplayUnit.KPA)
                )
            )
        )
        writer.record(
            EngineData(
                timestamp = 1_700_000_000_250L,
                values = linkedMapOf(
                    "Engine Speed" to ValueWithUnit(4540f, DisplayUnit.RPM),
                    "Manifold Relative Pressure" to ValueWithUnit(65f, DisplayUnit.KPA)
                )
            )
        )
        writer.record(
            EngineData(
                timestamp = 1_700_000_000_500L,
                values = linkedMapOf(
                    "Engine Speed" to ValueWithUnit(4560f, DisplayUnit.RPM),
                    "Boost Error*" to ValueWithUnit(37f, DisplayUnit.KPA)
                )
            )
        )

        writer.close()
        writer.join()

        val files = directory.listFiles { file -> file.extension == "csv" }!!.sortedBy { it.name }
        assertEquals(2, files.size)

        val first = files[0].readLines()
        assertEquals("timestamp,Engine Speed [rpm],Manifold Relative Pressure [kPa]", first[0])
        assertEquals(3, first.size)
        assertTrue(first[1].startsWith("2023-11-14T22:13:20.000Z,4507.0,64.0"))

        val second = files[1].readLines()
        assertEquals("timestamp,Engine Speed [rpm],Boost Error* [kPa]", second[0])
        assertEquals(2, second.size)
        assertTrue(second[1].endsWith(",4560.0,37.0"))
    }

    @Test
    fun quotesCsvColumnNamesWhenNeeded() = runBlocking {
        val directory = temporaryFolder.newFolder("quoted")
        val writer = MonitorCsvWriter(directory)

        writer.record(
            EngineData(
                timestamp = 1_700_000_000_000L,
                values = linkedMapOf(
                    "Calculated load, alternate" to ValueWithUnit(42f, DisplayUnit.PERCENT)
                )
            )
        )
        writer.close()
        writer.join()

        val header = directory.listFiles()!!.single().readLines().first()
        assertEquals("timestamp,\"Calculated load, alternate [%]\"", header)
    }
}
