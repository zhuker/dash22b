package com.example.dash22b.data

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogArchiverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun logDir(): File = temp.newFolder("logs")

    private fun write(dir: File, name: String, body: String, modified: Long? = null): File =
        File(dir, name).apply {
            writeText(body)
            modified?.let { setLastModified(it) }
        }

    @Test
    fun `lists monitor csvs and debug logs, ignoring everything else`() {
        val dir = logDir()
        write(dir, "monitor_2026-08-29_10-00-00-000.csv", "timestamp\n")
        write(dir, "app_logs.txt", "current\n")
        write(dir, "app_logs_2026-08-28_09-00-00.txt", "rotated\n")
        write(dir, "presets.json", "{}")
        write(dir, "notes.txt", "not a log")

        val logs = LogArchiver(dir).list()

        assertEquals(
            setOf(
                "monitor_2026-08-29_10-00-00-000.csv",
                "app_logs.txt",
                "app_logs_2026-08-28_09-00-00.txt"
            ),
            logs.map { it.name }.toSet()
        )
        assertEquals(
            LogArchiver.Kind.MONITOR_CSV,
            logs.first { it.name.endsWith(".csv") }.kind
        )
        assertEquals(
            listOf(LogArchiver.Kind.DEBUG_LOG, LogArchiver.Kind.DEBUG_LOG),
            logs.filter { it.name.startsWith("app_logs") }.map { it.kind }
        )
    }

    @Test
    fun `lists newest first`() {
        val dir = logDir()
        write(dir, "monitor_a.csv", "a", modified = 1_000_000L)
        write(dir, "app_logs.txt", "b", modified = 3_000_000L)
        write(dir, "app_logs_old.txt", "c", modified = 2_000_000L)

        assertEquals(
            listOf("app_logs.txt", "app_logs_old.txt", "monitor_a.csv"),
            LogArchiver(dir).list().map { it.name }
        )
    }

    @Test
    fun `list is empty for an empty or missing directory`() {
        assertTrue(LogArchiver(logDir()).list().isEmpty())
        assertTrue(LogArchiver(File(temp.root, "never-created")).list().isEmpty())
    }

    @Test
    fun `zip contains every log, flat, with contents intact`() {
        val dir = logDir()
        write(dir, "monitor_2026-08-29_10-00-00-000.csv", "timestamp,RPM\n1,2\n")
        write(dir, "app_logs.txt", "hello log\n")

        val zip = LogArchiver(dir).zipInto(File(temp.newFolder("out"), "logs.zip"))

        assertTrue(zip.exists())
        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertEquals(
                setOf("monitor_2026-08-29_10-00-00-000.csv", "app_logs.txt"),
                names.toSet()
            )
            val csv = archive.getInputStream(archive.getEntry("monitor_2026-08-29_10-00-00-000.csv"))
            assertEquals("timestamp,RPM\n1,2\n", csv.bufferedReader().readText())
        }
    }

    @Test
    fun `zip of nothing is still a readable empty archive`() {
        val zip = LogArchiver(logDir()).zipInto(File(temp.newFolder("out"), "logs.zip"))

        assertTrue(zip.exists())
        ZipFile(zip).use { assertEquals(0, it.entries().toList().size) }
    }

    @Test
    fun `zip creates its destination directory`() {
        val dir = logDir()
        write(dir, "app_logs.txt", "x")
        val nested = File(temp.root, "a/b/c/logs.zip")

        assertTrue(LogArchiver(dir).zipInto(nested).exists())
    }

    @Test
    fun `deleteAll removes rotated logs and csvs but truncates the active log`() {
        val dir = logDir()
        write(dir, "monitor_2026-08-29_10-00-00-000.csv", "timestamp\n1\n")
        write(dir, "app_logs_2026-08-28_09-00-00.txt", "rotated\n")
        val active = write(dir, "app_logs.txt", "still open\n")

        val result = LogArchiver(dir).deleteAll()

        assertEquals(3, result.deleted)
        assertEquals(0, result.failed)
        assertFalse(File(dir, "monitor_2026-08-29_10-00-00-000.csv").exists())
        assertFalse(File(dir, "app_logs_2026-08-28_09-00-00.txt").exists())
        // Truncated, not unlinked: DashApplication holds an open append handle on it.
        assertTrue(active.exists())
        assertEquals(0L, active.length())
    }

    @Test
    fun `deleteAll reports the space it freed and leaves non-logs alone`() {
        val dir = logDir()
        write(dir, "monitor_a.csv", "x".repeat(100))
        write(dir, "app_logs.txt", "y".repeat(50))
        write(dir, "presets.json", "{}")

        val result = LogArchiver(dir).deleteAll()

        assertEquals(150L, result.freedBytes)
        assertTrue(File(dir, "presets.json").exists())
        assertTrue(LogArchiver(dir).list().none { it.bytes > 0 })
    }

    @Test
    fun `deleteAll on an empty directory reports nothing`() {
        val result = LogArchiver(logDir()).deleteAll()

        assertEquals(0, result.deleted)
        assertEquals(0, result.failed)
        assertEquals(0L, result.freedBytes)
    }

    @Test
    fun `archive name is timestamped and zip-suffixed`() {
        val name = LogArchiver.archiveName(0L)

        assertTrue(name, name.startsWith("dash22b-logs-"))
        assertTrue(name, name.endsWith(".zip"))
    }

    @Test
    fun `formatBytes scales to B, KB and MB`() {
        assertEquals("512 B", LogArchiver.formatBytes(512))
        assertEquals("2 KB", LogArchiver.formatBytes(2048))
        assertEquals("1.5 MB", LogArchiver.formatBytes((1.5 * 1024 * 1024).toLong()))
    }
}
