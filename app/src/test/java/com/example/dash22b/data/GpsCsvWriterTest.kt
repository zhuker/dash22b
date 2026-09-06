package com.example.dash22b.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpsCsvWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fix(
        latitude: Double = 37.4219983,
        longitude: Double = -122.0840000,
        recordedAtMillis: Long = 1_700_000_000_000L,
        utcMillis: Long = 1_700_000_000_120L,
        elapsedRealtimeNanos: Long = 1_000_000_000L,
        speedKmh: Float? = 96.5f,
        altitudeMeters: Double? = 31.25,
        bearingDegrees: Float? = 182.5f,
        accuracyMeters: Float? = 3.5f,
        satellitesUsed: Int? = 11
    ) = GpsFix(
        latitude = latitude,
        longitude = longitude,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        utcMillis = utcMillis,
        recordedAtMillis = recordedAtMillis,
        speedKmh = speedKmh,
        altitudeMeters = altitudeMeters,
        bearingDegrees = bearingDegrees,
        accuracyMeters = accuracyMeters,
        satellitesUsed = satellitesUsed
    )

    @Test
    fun writesOneRowPerFixWithBothClocks() = runBlocking {
        val directory = temporaryFolder.newFolder("gps")
        val writer = GpsCsvWriter(directory)

        writer.record(fix())
        writer.record(fix(recordedAtMillis = 1_700_000_001_000L, speedKmh = 97.2f))
        writer.close()
        writer.join()

        val lines = directory.listFiles { file -> file.extension == "csv" }!!.single().readLines()
        assertEquals(GpsCsvWriter.HEADER, lines[0])
        assertEquals(3, lines.size)
        assertEquals(
            "2023-11-14T22:13:20.000Z,2023-11-14T22:13:20.120Z,1000000000," +
                "37.4219983,-122.0840000,96.50,31.25,182.50,3.50,11",
            lines[1]
        )
        assertTrue(lines[2].startsWith("2023-11-14T22:13:21.000Z"))
    }

    /**
     * Seven decimals is about a centimetre. Rounding position to a Float, as the monitor
     * CSV does with parameter values, would lose about a metre -- more than the receiver's
     * own resolution, and enough to move a trace off the road it was driven on.
     */
    @Test
    fun keepsPositionPrecisionAFloatWouldLose() = runBlocking {
        val directory = temporaryFolder.newFolder("precision")
        val writer = GpsCsvWriter(directory)

        val latitude = 37.4219983
        val longitude = -122.0840000
        writer.record(fix(latitude = latitude, longitude = longitude))
        writer.close()
        writer.join()

        val fields = directory.listFiles { file -> file.extension == "csv" }!!
            .single().readLines()[1].split(",")

        assertEquals(latitude, fields[3].toDouble(), 1e-7)
        assertEquals(longitude, fields[4].toDouble(), 1e-7)
        assertFalse("must not use exponent form", fields[3].contains("E", ignoreCase = true))
        // The same value through a Float is off by far more than the tolerance above.
        assertTrue(Math.abs(latitude.toFloat().toDouble() - latitude) > 1e-7)
    }

    /**
     * A receiver that reports no speed or no bearing must leave the field empty. Zero is a
     * real reading in both cases -- standing still, and due north.
     */
    @Test
    fun leavesMissingFieldsEmptyRatherThanZero() = runBlocking {
        val directory = temporaryFolder.newFolder("missing")
        val writer = GpsCsvWriter(directory)

        writer.record(
            fix(
                speedKmh = null,
                altitudeMeters = null,
                bearingDegrees = null,
                accuracyMeters = null,
                satellitesUsed = null
            )
        )
        writer.close()
        writer.join()

        val fields = directory.listFiles { file -> file.extension == "csv" }!!
            .single().readLines()[1].split(",")

        assertEquals(10, fields.size)
        assertEquals("", fields[5])
        assertEquals("", fields[6])
        assertEquals("", fields[7])
        assertEquals("", fields[8])
        assertEquals("", fields[9])
    }

    /**
     * "clear logs" can delete the track in progress. An open handle would otherwise keep
     * writing to an unlinked inode nobody can read.
     */
    @Test
    fun startsANewFileWhenTheCurrentOneIsDeleted() = runBlocking {
        val directory = temporaryFolder.newFolder("deleted")
        val writer = GpsCsvWriter(directory)

        writer.record(fix())
        // Flush is every ten rows, so wait for the file to exist before removing it.
        val first = waitForCsv(directory)
        assertTrue(first.delete())

        writer.record(fix(recordedAtMillis = 1_700_000_030_000L))
        writer.close()
        writer.join()

        val files = directory.listFiles { file -> file.extension == "csv" }!!
        assertEquals(1, files.size)
        val lines = files.single().readLines()
        assertEquals(GpsCsvWriter.HEADER, lines[0])
        assertTrue(lines[1].startsWith("2023-11-14T22:13:50.000Z"))
    }

    @Test
    fun namesTracksSoTheArchiverListsThem() = runBlocking {
        val directory = temporaryFolder.newFolder("named")
        val writer = GpsCsvWriter(directory)
        writer.record(fix())
        writer.close()
        writer.join()

        val log = LogArchiver(directory).list().single()
        assertEquals(LogArchiver.Kind.GPS_CSV, log.kind)
        assertTrue(log.name.startsWith(LogArchiver.GPS_CSV_PREFIX))
    }

    private fun waitForCsv(directory: java.io.File): java.io.File {
        repeat(200) {
            val files = directory.listFiles { file -> file.extension == "csv" }
            if (files != null && files.isNotEmpty()) return files.first()
            Thread.sleep(10)
        }
        throw AssertionError("no CSV was created")
    }
}
