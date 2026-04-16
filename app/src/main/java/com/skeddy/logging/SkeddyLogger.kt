package com.skeddy.logging

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Рівні логування для SkeddyLogger.
 */
enum class LogLevel(val priority: Int, val label: String) {
    DEBUG(0, "D"),
    INFO(1, "I"),
    WARN(2, "W"),
    ERROR(3, "E")
}

/**
 * Запис логу з усіма метаданими.
 */
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
    val screenState: String? = null
) {
    /**
     * Форматує запис для файлу/експорту.
     */
    fun format(): String {
        val base = "$timestamp [${level.label}] $tag: $message"
        val screen = screenState?.let { " [screen=$it]" } ?: ""
        val error = throwable?.let { "\n$it" } ?: ""
        return "$base$screen$error"
    }

    companion object {
        /**
         * Парсить рядок логу назад в LogEntry.
         * Використовується для читання збережених логів.
         */
        fun parse(line: String): LogEntry? {
            // Format: "2024-01-15T10:30:45.123 [I] Tag: Message [screen=MAIN]"
            val regex = Regex("""^(\S+)\s+\[([DIWE])]\s+(\S+):\s+(.+?)(?:\s+\[screen=([^]]+)])?$""")
            val match = regex.find(line) ?: return null

            val (timestamp, levelLabel, tag, message, screen) = match.destructured
            val level = LogLevel.entries.find { it.label == levelLabel } ?: return null

            return LogEntry(
                timestamp = timestamp,
                level = level,
                tag = tag,
                message = message,
                screenState = screen.takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * Singleton логер для Skeddy з підтримкою файлового логування та ротації.
 *
 * Особливості:
 * - Рівні логування: DEBUG, INFO, WARN, ERROR
 * - Ротація: максимум 1000 записів, найстаріші видаляються
 * - Thread-safe через ConcurrentLinkedDeque та single-thread executor
 * - Буферизований запис для продуктивності
 * - Підтримка stack trace для exceptions
 * - Опціональний screen state для контексту
 *
 * Використання:
 * ```
 * SkeddyLogger.init(context)
 * SkeddyLogger.i("MyTag", "Info message")
 * SkeddyLogger.e("MyTag", "Error occurred", exception)
 * ```
 */
object SkeddyLogger {
    private const val TAG = "SkeddyLogger"
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val MAX_ENTRIES = 1000
    private const val FLUSH_THRESHOLD = 10

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkeddyLogger-Writer").apply { isDaemon = true }
    }

    private var logFile: File? = null
    private var isInitialized = AtomicBoolean(false)
    private var pendingWrites = 0
    private val pendingWritesLock = Any()

    // Поточний screen state для автоматичного додавання до логів
    @Volatile
    var currentScreenState: String? = null

    // Мінімальний рівень логування (логи нижче цього рівня ігноруються)
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    // Чи виводити логи також в Android Logcat
    @Volatile
    var logToLogcat: Boolean = true

    /**
     * Ініціалізує логер з контекстом додатку.
     * Має бути викликано один раз при старті Application.
     *
     * @param context Application context
     */
    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "SkeddyLogger already initialized")
            return
        }

        logFile = File(context.filesDir, LOG_FILE_NAME)
        loadExistingLogs()
        Log.i(TAG, "SkeddyLogger initialized. Log file: ${logFile?.absolutePath}")
    }

    /**
     * Логування на рівні DEBUG.
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    /**
     * Логування на рівні INFO.
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    /**
     * Логування на рівні WARN.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    /**
     * Логування на рівні ERROR.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    /**
     * Основний метод логування.
     */
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        if (level.priority < minLevel.priority) {
            return
        }

        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.let { getStackTraceString(it) },
            screenState = currentScreenState
        )

        // Додаємо в пам'ять
        entries.addLast(entry)

        // Ротація: видаляємо найстаріші якщо перевищено ліміт
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }

        // Виводимо в Logcat якщо увімкнено
        if (logToLogcat) {
            logToAndroid(entry)
        }

        // Асинхронний запис у файл
        scheduleWrite(entry)
    }

    /**
     * Експортує всі логи як один рядок.
     */
    fun exportLogs(): String {
        return entries.joinToString("\n") { it.format() }
    }

    /**
     * Повертає всі записи логів.
     */
    fun getEntries(): List<LogEntry> {
        return entries.toList()
    }

    /**
     * Повертає записи логів відфільтровані за рівнем.
     */
    fun getEntries(minLevel: LogLevel): List<LogEntry> {
        return entries.filter { it.level.priority >= minLevel.priority }
    }

    /**
     * Повертає записи логів відфільтровані за тегом.
     */
    fun getEntriesByTag(tag: String): List<LogEntry> {
        return entries.filter { it.tag == tag }
    }

    /**
     * Очищає всі логи.
     */
    fun clear() {
        entries.clear()
        writeExecutor.execute {
            logFile?.let { file ->
                if (file.exists()) {
                    file.writeText("")
                }
            }
        }
    }

    /**
     * Примусово записує всі pending логи у файл.
     * Блокує до завершення запису (з таймаутом 5 секунд).
     */
    fun flush() {
        try {
            writeExecutor.submit { flushToFile() }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "flush() failed", e)
        }
    }

    /**
     * Повертає кількість записів.
     */
    fun getEntryCount(): Int = entries.size

    /**
     * Повертає шлях до файлу логів.
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    // ==================== Private Methods ====================

    private fun loadExistingLogs() {
        logFile?.let { file ->
            if (file.exists()) {
                try {
                    val lines = file.readLines().takeLast(MAX_ENTRIES)
                    lines.forEach { line ->
                        LogEntry.parse(line)?.let { entries.addLast(it) }
                    }
                    Log.d(TAG, "Loaded ${entries.size} existing log entries")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load existing logs", e)
                }
            }
        }
    }

    private fun scheduleWrite(entry: LogEntry) {
        if (!isInitialized.get() || logFile == null) {
            return
        }

        synchronized(pendingWritesLock) {
            pendingWrites++
            if (pendingWrites >= FLUSH_THRESHOLD) {
                pendingWrites = 0
                writeExecutor.execute { flushToFile() }
            } else {
                writeExecutor.execute { appendToFile(entry) }
            }
        }
    }

    private fun appendToFile(entry: LogEntry) {
        logFile?.let { file ->
            try {
                BufferedWriter(FileWriter(file, true)).use { writer ->
                    writer.write(entry.format())
                    writer.newLine()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append log entry", e)
            }
        }
    }

    private fun flushToFile() {
        logFile?.let { file ->
            try {
                // Перезаписуємо файл з останніми MAX_ENTRIES записами
                val entriesToWrite = entries.toList().takeLast(MAX_ENTRIES)
                BufferedWriter(FileWriter(file, false)).use { writer ->
                    entriesToWrite.forEach { entry ->
                        writer.write(entry.format())
                        writer.newLine()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush logs to file", e)
            }
        }
    }

    private fun logToAndroid(entry: LogEntry) {
        val fullMessage = if (entry.screenState != null) {
            "${entry.message} [screen=${entry.screenState}]"
        } else {
            entry.message
        }

        when (entry.level) {
            LogLevel.DEBUG -> Log.d(entry.tag, fullMessage)
            LogLevel.INFO -> Log.i(entry.tag, fullMessage)
            LogLevel.WARN -> Log.w(entry.tag, fullMessage)
            LogLevel.ERROR -> Log.e(entry.tag, fullMessage)
        }

        entry.throwable?.let {
            Log.e(entry.tag, it)
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString().trim()
    }

    // ==================== Testing Support ====================

    /**
     * Скидає стан логера (для тестування).
     */
    internal fun reset() {
        entries.clear()
        currentScreenState = null
        minLevel = LogLevel.DEBUG
        logToLogcat = true
        isInitialized.set(false)
        logFile = null
    }

    /**
     * Ініціалізує логер з кастомним файлом (для тестування).
     */
    internal fun initWithFile(file: File) {
        if (isInitialized.getAndSet(true)) {
            reset()
            isInitialized.set(true)
        }
        logFile = file
        loadExistingLogs()
    }
}
