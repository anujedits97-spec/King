package dev.kairo.myra

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash writer before anything else runs. */
class MyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Saves the last crash to a text file in the app's own folder, so it can be read without a computer:
 * Android/data/dev.kairo.myra/files/myra_crash_log.txt. The file is cleared once it passes 100 KB.
 * Nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "myra_crash_log.txt"
    private const val MAX_BYTES = 100_000L

    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        if (f.exists() && f.length() > MAX_BYTES) f.delete()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        f.appendText("[$stamp] thread=${thread.name}\n$trace\n")
    }
}
