package com.cssupport.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple crash logger that writes crash info to a file in app's internal storage.
 * The crash file is shown to the user on next launch via WelcomeActivity.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val crashInfo = """
                    |Crash at: $timestamp
                    |Thread: ${thread.name}
                    |Exception: ${throwable.javaClass.name}: ${throwable.message}
                    |
                    |Stack trace:
                    |$sw
                """.trimMargin()

                val file = File(context.filesDir, CRASH_FILE)
                file.writeText(crashInfo)
                Log.e(TAG, "Crash logged to ${file.absolutePath}")
                Log.e(TAG, crashInfo)
            } catch (_: Exception) {
                // Can't save crash info, just let the default handler run.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getLastCrash(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        return if (file.exists()) file.readText() else null
    }

    fun clearLastCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }
}
