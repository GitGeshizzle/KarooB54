package io.github.gitgeshizzle.karoob54

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timber tree that appends log lines to a file under the app's external files dir, so BLE
 * connection events survive a ride and can be read afterwards — we can't tether on the bike,
 * and logcat's small ring buffer is overwritten long before we're home.
 *
 * Pull it with:
 *   adb pull /sdcard/Android/data/io.github.gitgeshizzle.karoob54/files/b54-log.txt
 *
 * Low volume (only what the extension logs via Timber — connection lifecycle, not per-second
 * keepalives), with simple size-capped rotation (current + one backup). Writes are offloaded
 * to a single background thread so a BLE callback is never blocked on file I/O, and logging can
 * never crash the app.
 */
class FileLogTree(context: Context) : Timber.Tree() {

    private val dir: File? = context.getExternalFilesDir(null)
    private val logFile = File(dir, FILE)
    private val executor = Executors.newSingleThreadExecutor()
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Absolute path of the current log file, for surfacing to the user. */
    val path: String get() = logFile.absolutePath

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (dir == null) return
        val line = buildString {
            append(timestamp.format(Date()))
            append(' ').append(priorityChar(priority)).append('/').append(tag ?: "-")
            append(": ").append(message)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
        }
        executor.execute {
            try {
                rotateIfNeeded()
                logFile.appendText(line + "\n")
            } catch (_: Exception) {
                // Logging must never take down the extension.
            }
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.length() <= MAX_BYTES) return
        val backup = File(dir, "$FILE.1")
        if (backup.exists()) backup.delete()
        logFile.renameTo(backup)
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> '?'
    }

    companion object {
        const val FILE = "b54-log.txt"
        private const val MAX_BYTES = 512 * 1024L
    }
}
