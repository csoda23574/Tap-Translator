package com.taptranslator.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "AppLogger"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logsFlow = MutableStateFlow("")
    val logsFlow: StateFlow<String> = _logsFlow.asStateFlow()

    private val memoryLogs = mutableListOf<String>()
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(externalDir, "tap_translator_debug.log")
            // Read existing logs if any
            if (logFile?.exists() == true) {
                val existing = logFile?.readText().orEmpty().takeLast(5000)
                _logsFlow.value = existing
            }
            d(TAG, "Logger initialized. File path: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init log file", e)
        }
    }

    @Synchronized
    fun d(tag: String, message: String) {
        logInternal("DEBUG", tag, message, null)
    }

    @Synchronized
    fun i(tag: String, message: String) {
        logInternal("INFO", tag, message, null)
    }

    @Synchronized
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        logInternal("WARN", tag, message, throwable)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        logInternal("ERROR", tag, message, throwable)
    }

    private fun logInternal(level: String, tag: String, message: String, throwable: Throwable?) {
        val time = dateFormat.format(Date())
        val stackTrace = throwable?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
        val entry = "[$time] [$level] [$tag] $message$stackTrace"

        // System Logcat
        when (level) {
            "DEBUG" -> Log.d(tag, message, throwable)
            "INFO" -> Log.i(tag, message, throwable)
            "WARN" -> Log.w(tag, message, throwable)
            "ERROR" -> Log.e(tag, message, throwable)
        }

        // Memory buffer (max 150 lines)
        memoryLogs.add(entry)
        if (memoryLogs.size > 150) {
            memoryLogs.removeAt(0)
        }
        val fullMemoryText = memoryLogs.joinToString("\n")
        _logsFlow.value = fullMemoryText

        // Append to file
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.appendLine(entry)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing to log file", e)
        }
    }

    @Synchronized
    fun getFullLogs(): String {
        return try {
            logFile?.readText() ?: memoryLogs.joinToString("\n")
        } catch (e: Exception) {
            memoryLogs.joinToString("\n")
        }
    }

    @Synchronized
    fun clearLogs() {
        memoryLogs.clear()
        _logsFlow.value = ""
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing log file", e)
        }
    }
}
