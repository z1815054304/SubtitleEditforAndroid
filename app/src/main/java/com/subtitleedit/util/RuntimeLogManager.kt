package com.subtitleedit.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process as AndroidProcess
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLogManager {
    private const val LOG_DIR = "runtime_logs"
    private const val PREFS_NAME = "runtime_logs"
    private const val KEY_CLEAR_TIME = "clear_time"
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L
    private const val MAX_LOGCAT_CRASH_LINES = 400

    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    @Volatile
    private var installed = false
    private var appContext: Context? = null
    private var writer: BufferedWriter? = null
    private var activeLogFile: File? = null
    private var logcatProcess: java.lang.Process? = null
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    data class Snapshot(
        val content: String,
        val file: File,
        val capturedAt: Long,
        val packageName: String
    )

    fun install(application: Application) {
        synchronized(lock) {
            if (installed) return
            installed = true
            appContext = application.applicationContext
            pruneOldLogs(application, System.currentTimeMillis())
            openWriterLocked(application, System.currentTimeMillis())
            writeLocked("INFO", "RuntimeLog", "日志系统已启动，后台实时写入文件")
            previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordCrash(thread, throwable)
                previousExceptionHandler?.uncaughtException(thread, throwable)
            }
            application.registerActivityLifecycleCallbacks(createLifecycleCallbacks())
            startLogcatReader()
        }
    }

    fun captureLastDay(context: Context): Snapshot {
        ensureInstalled(context)
        val now = System.currentTimeMillis()
        val appContext = context.applicationContext
        val since = maxOf(now - RETENTION_MS, getClearTime(appContext))
        pruneOldLogs(context, now)
        flush()

        val packageName = appContext.packageName
        val persisted = readPersistedLogs(appContext, since)
        val content = buildString {
            appendLine("SubtitleEdit for Android 运行日志")
            appendLine("包名：$packageName")
            appendLine("采集时间：${displayTimeFormat.format(Date(now))}")
            appendLine("有效范围：本应用最近 24 小时，且不早于上次清空时间")
            appendLine("来源：应用启动后实时文件日志、页面生命周期、崩溃兜底记录、后台 logcat")
            appendLine()
            append(if (persisted.isBlank()) "暂无可读取的日志。" else persisted)
            appendLine()
        }

        val file = File(logDir(appContext), "runtime-snapshot-${fileTimeFormat.format(Date(now))}.txt")
        file.writeText(content, Charsets.UTF_8)
        return Snapshot(content, file, now, packageName)
    }

    fun exportFileName(now: Long = System.currentTimeMillis()): String {
        return "subtitleedit-log-${fileTimeFormat.format(Date(now))}.txt"
    }

    fun clear(context: Context) {
        ensureInstalled(context)
        val now = System.currentTimeMillis()
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CLEAR_TIME, now)
            .apply()
        synchronized(lock) {
            closeWriterLocked()
            logDir(appContext).listFiles()?.forEach { it.delete() }
            openWriterLocked(appContext, now)
            writeLocked("INFO", "RuntimeLog", "日志已清空并重新开始记录")
        }
    }

    fun i(tag: String, message: String) = write("INFO", tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write("WARN", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("ERROR", tag, message, throwable)

    private fun getClearTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CLEAR_TIME, 0L)
    }

    private fun logDir(context: Context): File {
        return File(context.cacheDir, LOG_DIR).apply { mkdirs() }
    }

    private fun pruneOldLogs(context: Context, now: Long) {
        logDir(context).listFiles()
            ?.filter { it.isFile && now - it.lastModified() > RETENTION_MS }
            ?.forEach { it.delete() }
    }

    private fun ensureInstalled(context: Context) {
        if (installed) return
        val application = context.applicationContext as? Application ?: return
        install(application)
    }

    private fun openWriterLocked(context: Context, now: Long) {
        if (writer != null) return
        activeLogFile = File(logDir(context), "runtime-live-${fileTimeFormat.format(Date(now))}.txt")
        writer = BufferedWriter(FileWriter(activeLogFile, true))
    }

    private fun closeWriterLocked() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        activeLogFile = null
    }

    private fun flush() {
        synchronized(lock) {
            runCatching { writer?.flush() }
        }
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val context = appContext ?: return
            openWriterLocked(context, System.currentTimeMillis())
            writeLocked(level, tag, message, throwable)
        }
    }

    private fun writeLocked(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = displayTimeFormat.format(Date())
        writer?.apply {
            write("$timestamp $level/$tag: $message")
            newLine()
            if (throwable != null) {
                write(stackTraceToString(throwable))
                newLine()
            }
            flush()
        }
    }

    private fun readPersistedLogs(context: Context, since: Long): String {
        val activeFile = activeLogFile?.takeIf { it.exists() }
        val historicalFiles = logDir(context).listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("runtime-live-") &&
                    file.lastModified() >= since &&
                    file != activeFile
            }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        val files = if (activeFile != null) historicalFiles + activeFile else historicalFiles
        return files.distinct()
            .joinToString("\n") { file ->
                runCatching {
                    "===== ${file.name} =====\n${file.readText(Charsets.UTF_8).trim()}"
                }.getOrElse {
                    "===== ${file.name} =====\n读取失败：${it.message}"
                }
            }
    }

    private fun createLifecycleCallbacks(): Application.ActivityLifecycleCallbacks {
        return object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                write("INFO", "Navigation", "${activity.localClassName} created")
            }

            override fun onActivityStarted(activity: Activity) {
                write("INFO", "Navigation", "${activity.localClassName} started")
            }

            override fun onActivityResumed(activity: Activity) {
                write("INFO", "Navigation", "${activity.localClassName} resumed")
            }

            override fun onActivityPaused(activity: Activity) {
                write("INFO", "Navigation", "${activity.localClassName} paused")
            }

            override fun onActivityStopped(activity: Activity) {
                write("INFO", "Navigation", "${activity.localClassName} stopped")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                write("INFO", "Navigation", "${activity.localClassName} destroyed")
            }
        }
    }

    private fun startLogcatReader() {
        Thread {
            val command = listOf("logcat", "-v", "time", "--pid", AndroidProcess.myPid().toString())
            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                synchronized(lock) {
                    logcatProcess = process
                }
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        write("LOGCAT", "logcat", line)
                    }
                }
            } catch (e: Exception) {
                write("WARN", "RuntimeLog", "后台 logcat 读取失败：${e.message}", e)
            } finally {
                synchronized(lock) {
                    logcatProcess = null
                }
            }
        }.apply {
            name = "RuntimeLogcatReader"
            isDaemon = true
            start()
        }
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        synchronized(lock) {
            val context = appContext
            if (context != null) {
                openWriterLocked(context, System.currentTimeMillis())
            }
            writeLocked("FATAL", "Crash", "未捕获异常，线程=${thread.name}", throwable)
            val recentLogcat = runLogcat(
                listOf(
                    "logcat",
                    "-d",
                    "-v",
                    "time",
                    "-t",
                    MAX_LOGCAT_CRASH_LINES.toString(),
                    "--pid",
                    AndroidProcess.myPid().toString()
                )
            ).trim()
            if (recentLogcat.isNotBlank()) {
                writeLocked("FATAL", "CrashLogcat", "\n$recentLogcat")
            }
            runCatching { writer?.flush() }
        }
    }

    private fun runLogcat(command: List<String>): String {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "读取 logcat 失败：${e.message}"
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        return stringWriter.toString()
    }
}
