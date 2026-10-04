package com.nuvio.tv.livetv.crash

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * Shows crashes on screen instead of just closing the app, so they can be reported without a
 * computer. Installed by [CrashCatcherInitProvider] before anything else in the app starts.
 */
object CrashCatcher {
    private const val TAG = "LiveTvCrashCatcher"
    private const val FILE_NAME = "last_crash.txt"
    const val EXTRA_REPORT = "report"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(thread, error)
                runCatching { File(app.filesDir, FILE_NAME).writeText(report) }
                Log.e(TAG, report)
                app.startActivity(
                    Intent(app, CrashReportActivity::class.java)
                        .putExtra(EXTRA_REPORT, report)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (t: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun lastReport(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun buildReport(thread: Thread, error: Throwable): String {
        // Put the root cause first: it's the most useful line when reading from a TV screen.
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return buildString {
            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("ROOT CAUSE: $root")
            root.stackTrace.take(12).forEach { appendLine("    at $it") }
            appendLine()
            appendLine("FULL TRACE:")
            append(Log.getStackTraceString(error))
        }
    }
}

/** Runs before Application.onCreate, so crashes during startup are caught too. */
class CrashCatcherInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { CrashCatcher.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
