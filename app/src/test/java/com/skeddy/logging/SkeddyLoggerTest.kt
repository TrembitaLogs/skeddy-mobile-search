package com.skeddy.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SkeddyLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logFile: File

    @Before
    fun setup() {
        SkeddyLogger.reset()
        logFile = tempFolder.newFile("test_logs.txt")
        SkeddyLogger.logToLogcat = false // Disable Logcat output for tests
        SkeddyLogger.initWithFile(logFile)
    }

    @After
    fun tearDown() {
        SkeddyLogger.reset()
    }

    // ==================== LogLevel Tests ====================

    @Test
    fun `LogLevel DEBUG has lowest priority`() {
        assertEquals(0, LogLevel.DEBUG.priority)
        assertEquals("D", LogLevel.DEBUG.label)
    }

    @Test
    fun `LogLevel INFO has priority 1`() {
        assertEquals(1, LogLevel.INFO.priority)
        assertEquals("I", LogLevel.INFO.label)
    }

    @Test
    fun `LogLevel WARN has priority 2`() {
        assertEquals(2, LogLevel.WARN.priority)
        assertEquals("W", LogLevel.WARN.label)
    }

    @Test
    fun `LogLevel ERROR has highest priority`() {
        assertEquals(3, LogLevel.ERROR.priority)
        assertEquals("E", LogLevel.ERROR.label)
    }

    @Test
    fun `LogLevel priorities are in correct order`() {
        assertTrue(LogLevel.DEBUG.priority < LogLevel.INFO.priority)
        assertTrue(LogLevel.INFO.priority < LogLevel.WARN.priority)
        assertTrue(LogLevel.WARN.priority < LogLevel.ERROR.priority)
    }

    // ==================== Basic Logging Tests ====================

    @Test
    fun `d() creates DEBUG entry`() {
        SkeddyLogger.d("TestTag", "Debug message")

        val entries = SkeddyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals(LogLevel.DEBUG, entries[0].level)
        assertEquals("TestTag", entries[0].tag)
        assertEquals("Debug message", entries[0].message)
    }

    @Test
    fun `i() creates INFO entry`() {
        SkeddyLogger.i("TestTag", "Info message")

        val entries = SkeddyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals(LogLevel.INFO, entries[0].level)
    }

    @Test
    fun `w() creates WARN entry`() {
        SkeddyLogger.w("TestTag", "Warn message")

        val entries = SkeddyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals(LogLevel.WARN, entries[0].level)
    }

    @Test
    fun `e() creates ERROR entry`() {
        SkeddyLogger.e("TestTag", "Error message")

        val entries = SkeddyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals(LogLevel.ERROR, entries[0].level)
    }

    @Test
    fun `log entry contains timestamp`() {
        SkeddyLogger.i("Tag", "Message")

        val entries = SkeddyLogger.getEntries()
        assertTrue(entries[0].timestamp.isNotEmpty())
        // Check ISO 8601 format: YYYY-MM-DDTHH:MM:SS.SSS
        assertTrue(entries[0].timestamp.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}""")))
    }

    @Test
    fun `log entry contains exception stack trace`() {
        val exception = RuntimeException("Test exception")
        SkeddyLogger.e("Tag", "Error", exception)

        val entries = SkeddyLogger.getEntries()
        assertNotNull(entries[0].throwable)
        assertTrue(entries[0].throwable!!.contains("RuntimeException"))
        assertTrue(entries[0].throwable!!.contains("Test exception"))
    }

    @Test
    fun `log entry contains screen state when set`() {
        SkeddyLogger.currentScreenState = "MAIN_SCREEN"
        SkeddyLogger.i("Tag", "Message")

        val entries = SkeddyLogger.getEntries()
        assertEquals("MAIN_SCREEN", entries[0].screenState)
    }

    @Test
    fun `log entry has null screen state when not set`() {
        SkeddyLogger.currentScreenState = null
        SkeddyLogger.i("Tag", "Message")

        val entries = SkeddyLogger.getEntries()
        assertNull(entries[0].screenState)
    }

    // ==================== Log Level Filtering ====================

    @Test
    fun `minLevel DEBUG allows all logs`() {
        SkeddyLogger.minLevel = LogLevel.DEBUG

        SkeddyLogger.d("Tag", "Debug")
        SkeddyLogger.i("Tag", "Info")
        SkeddyLogger.w("Tag", "Warn")
        SkeddyLogger.e("Tag", "Error")

        assertEquals(4, SkeddyLogger.getEntryCount())
    }

    @Test
    fun `minLevel INFO filters DEBUG`() {
        SkeddyLogger.minLevel = LogLevel.INFO

        SkeddyLogger.d("Tag", "Debug")
        SkeddyLogger.i("Tag", "Info")
        SkeddyLogger.w("Tag", "Warn")
        SkeddyLogger.e("Tag", "Error")

        assertEquals(3, SkeddyLogger.getEntryCount())
        assertFalse(SkeddyLogger.getEntries().any { it.level == LogLevel.DEBUG })
    }

    @Test
    fun `minLevel WARN filters DEBUG and INFO`() {
        SkeddyLogger.minLevel = LogLevel.WARN

        SkeddyLogger.d("Tag", "Debug")
        SkeddyLogger.i("Tag", "Info")
        SkeddyLogger.w("Tag", "Warn")
        SkeddyLogger.e("Tag", "Error")

        assertEquals(2, SkeddyLogger.getEntryCount())
    }

    @Test
    fun `minLevel ERROR filters all except ERROR`() {
        SkeddyLogger.minLevel = LogLevel.ERROR

        SkeddyLogger.d("Tag", "Debug")
        SkeddyLogger.i("Tag", "Info")
        SkeddyLogger.w("Tag", "Warn")
        SkeddyLogger.e("Tag", "Error")

        assertEquals(1, SkeddyLogger.getEntryCount())
        assertEquals(LogLevel.ERROR, SkeddyLogger.getEntries()[0].level)
    }

    // ==================== Rotation Tests ====================

    @Test
    fun `rotation keeps only MAX_ENTRIES logs`() {
        // Log more than MAX_ENTRIES (1000)
        repeat(1050) { i ->
            SkeddyLogger.i("Tag", "Message $i")
        }

        assertEquals(1000, SkeddyLogger.getEntryCount())
    }

    @Test
    fun `rotation removes oldest entries first`() {
        repeat(1010) { i ->
            SkeddyLogger.i("Tag", "Message $i")
        }

        val entries = SkeddyLogger.getEntries()
        // Oldest entries (0-9) should be removed, first entry should be Message 10
        assertEquals("Message 10", entries.first().message)
        assertEquals("Message 1009", entries.last().message)
    }

    // ==================== Query Methods Tests ====================

    @Test
    fun `getEntries returns all entries`() {
        SkeddyLogger.d("Tag1", "Debug")
        SkeddyLogger.i("Tag2", "Info")
        SkeddyLogger.e("Tag3", "Error")

        val entries = SkeddyLogger.getEntries()
        assertEquals(3, entries.size)
    }

    @Test
    fun `getEntries with minLevel filters correctly`() {
        SkeddyLogger.d("Tag", "Debug")
        SkeddyLogger.i("Tag", "Info")
        SkeddyLogger.w("Tag", "Warn")
        SkeddyLogger.e("Tag", "Error")

        val warnAndAbove = SkeddyLogger.getEntries(LogLevel.WARN)
        assertEquals(2, warnAndAbove.size)
        assertTrue(warnAndAbove.all { it.level.priority >= LogLevel.WARN.priority })
    }

    @Test
    fun `getEntriesByTag filters by tag`() {
        SkeddyLogger.i("Tag1", "Message 1")
        SkeddyLogger.i("Tag2", "Message 2")
        SkeddyLogger.i("Tag1", "Message 3")

        val tag1Entries = SkeddyLogger.getEntriesByTag("Tag1")
        assertEquals(2, tag1Entries.size)
        assertTrue(tag1Entries.all { it.tag == "Tag1" })
    }

    @Test
    fun `getEntriesByTag returns empty for non-existent tag`() {
        SkeddyLogger.i("Tag1", "Message")

        val entries = SkeddyLogger.getEntriesByTag("NonExistent")
        assertTrue(entries.isEmpty())
    }

    // ==================== Export Tests ====================

    @Test
    fun `exportLogs returns formatted log entries`() {
        SkeddyLogger.i("Tag1", "Message 1")
        SkeddyLogger.e("Tag2", "Message 2")

        val export = SkeddyLogger.exportLogs()

        assertTrue(export.contains("[I] Tag1: Message 1"))
        assertTrue(export.contains("[E] Tag2: Message 2"))
    }

    @Test
    fun `exportLogs includes screen state`() {
        SkeddyLogger.currentScreenState = "SIDE_MENU"
        SkeddyLogger.i("Tag", "Message")

        val export = SkeddyLogger.exportLogs()
        assertTrue(export.contains("[screen=SIDE_MENU]"))
    }

    @Test
    fun `exportLogs returns empty string when no entries`() {
        val export = SkeddyLogger.exportLogs()
        assertTrue(export.isEmpty())
    }

    // ==================== Clear Tests ====================

    @Test
    fun `clear removes all entries`() {
        SkeddyLogger.i("Tag", "Message 1")
        SkeddyLogger.i("Tag", "Message 2")
        assertEquals(2, SkeddyLogger.getEntryCount())

        SkeddyLogger.clear()

        assertEquals(0, SkeddyLogger.getEntryCount())
    }

    // ==================== LogEntry Tests ====================

    @Test
    fun `LogEntry format produces correct string`() {
        val entry = LogEntry(
            timestamp = "2024-01-15T10:30:45.123",
            level = LogLevel.INFO,
            tag = "TestTag",
            message = "Test message",
            screenState = "MAIN"
        )

        val formatted = entry.format()
        assertEquals("2024-01-15T10:30:45.123 [I] TestTag: Test message [screen=MAIN]", formatted)
    }

    @Test
    fun `LogEntry format without screen state`() {
        val entry = LogEntry(
            timestamp = "2024-01-15T10:30:45.123",
            level = LogLevel.ERROR,
            tag = "Tag",
            message = "Error message"
        )

        val formatted = entry.format()
        assertEquals("2024-01-15T10:30:45.123 [E] Tag: Error message", formatted)
    }

    @Test
    fun `LogEntry format includes throwable`() {
        val entry = LogEntry(
            timestamp = "2024-01-15T10:30:45.123",
            level = LogLevel.ERROR,
            tag = "Tag",
            message = "Error",
            throwable = "java.lang.RuntimeException: Test"
        )

        val formatted = entry.format()
        assertTrue(formatted.contains("java.lang.RuntimeException: Test"))
    }

    @Test
    fun `LogEntry parse parses valid log line`() {
        val line = "2024-01-15T10:30:45.123 [I] TestTag: Test message"
        val entry = LogEntry.parse(line)

        assertNotNull(entry)
        assertEquals("2024-01-15T10:30:45.123", entry!!.timestamp)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("TestTag", entry.tag)
        assertEquals("Test message", entry.message)
    }

    @Test
    fun `LogEntry parse handles screen state`() {
        val line = "2024-01-15T10:30:45.123 [W] Tag: Message [screen=SIDE_MENU]"
        val entry = LogEntry.parse(line)

        assertNotNull(entry)
        assertEquals("SIDE_MENU", entry!!.screenState)
    }

    @Test
    fun `LogEntry parse returns null for invalid line`() {
        val invalidLine = "This is not a valid log line"
        val entry = LogEntry.parse(invalidLine)

        assertNull(entry)
    }

    // ==================== Thread Safety Tests ====================

    @Test
    fun `concurrent logging is thread-safe`() {
        val threadCount = 10
        val logsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { threadIndex ->
            executor.execute {
                try {
                    repeat(logsPerThread) { logIndex ->
                        SkeddyLogger.i("Thread$threadIndex", "Message $logIndex")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // All logs should be recorded (1000 total, but rotation keeps max 1000)
        assertEquals(1000, SkeddyLogger.getEntryCount())
    }

    @Test
    fun `concurrent screen state updates are safe`() {
        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val screens = listOf("MAIN", "SIDE_MENU", "SCHEDULED_RIDES", "RIDE_DETAILS", "UNKNOWN")

        repeat(threadCount) { index ->
            executor.execute {
                try {
                    repeat(100) {
                        SkeddyLogger.currentScreenState = screens[index]
                        SkeddyLogger.i("Tag", "Message")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Should not throw any exceptions
        assertTrue(SkeddyLogger.getEntryCount() > 0)
    }

    // ==================== File Persistence Tests ====================

    @Test
    fun `logs are written to file`() {
        SkeddyLogger.i("Tag", "Test message")
        SkeddyLogger.flush()

        val fileContent = logFile.readText()
        assertTrue(fileContent.contains("Test message"))
    }

    @Test
    fun `logs are loaded on init`() {
        // Write some logs
        SkeddyLogger.i("Tag", "Persisted message")
        SkeddyLogger.flush()

        // Reset and reinitialize
        SkeddyLogger.reset()
        SkeddyLogger.logToLogcat = false
        SkeddyLogger.initWithFile(logFile)

        // Logs should be loaded
        val entries = SkeddyLogger.getEntries()
        assertTrue(entries.any { it.message == "Persisted message" })
    }

    // ==================== Edge Cases ====================

    @Test
    fun `empty message is logged`() {
        SkeddyLogger.i("Tag", "")

        val entries = SkeddyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals("", entries[0].message)
    }

    @Test
    fun `special characters in message are handled`() {
        val specialMessage = "Test with\nnewline\tand\ttabs and émojis: \uD83D\uDE00"
        SkeddyLogger.i("Tag", specialMessage)

        val entries = SkeddyLogger.getEntries()
        assertEquals(specialMessage, entries[0].message)
    }

    @Test
    fun `very long message is handled`() {
        val longMessage = "A".repeat(10000)
        SkeddyLogger.i("Tag", longMessage)

        val entries = SkeddyLogger.getEntries()
        assertEquals(longMessage, entries[0].message)
    }

    @Test
    fun `nested exception stack trace is captured`() {
        val cause = IllegalArgumentException("Root cause")
        val exception = RuntimeException("Wrapper", cause)
        SkeddyLogger.e("Tag", "Error", exception)

        val entries = SkeddyLogger.getEntries()
        assertNotNull(entries[0].throwable)
        assertTrue(entries[0].throwable!!.contains("Root cause"))
        assertTrue(entries[0].throwable!!.contains("Wrapper"))
    }
}
