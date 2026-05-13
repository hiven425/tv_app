package com.tvcast.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to `filesDir/crashes/<timestamp>.log` before delegating to the
 * platform handler. Useful for diagnosing Sony TV-side issues over `adb pull` without needing
 * `adb logcat` to be running at the moment of the crash.
 */
class TvCastApp : Application() {

    companion object {
        private const val TAG = "TvCastApp"
        const val CRASH_DIR = "crashes"
        private const val MAX_FILES = 20
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val dir = File(filesDir, CRASH_DIR).apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(dir, thread, throwable)
                trimOldCrashes(dir)
            } catch (e: Exception) {
                Log.e(TAG, "crash handler itself failed", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(dir: File, thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.log")
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("TvCast crash log")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${thread.name}")
            pw.println("VM: ${System.getProperty("java.vm.version")}")
            pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.FINGERPRINT})")
            pw.println("Android: API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.VERSION.RELEASE}")
            pw.println("App: ${packageName} v${packageManager.getPackageInfo(packageName, 0).versionName}")
            pw.println()
            throwable.printStackTrace(pw)
        }
        file.writeText(sw.toString())
        Log.e(TAG, "crash written to ${file.absolutePath}")
    }

    private fun trimOldCrashes(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        for ((idx, file) in files.withIndex()) {
            if (idx >= MAX_FILES) runCatching { file.delete() }
        }
    }
}
