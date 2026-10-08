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
        // A freeze the app died in last time (Android closes frozen apps without an error on
        // many TV boxes): show what it was stuck on, like a crash.
        runCatching {
            val frozen = File(app.filesDir, FREEZE_FILE)
            if (frozen.exists()) {
                val report = frozen.readText()
                frozen.delete()
                File(app.filesDir, FILE_NAME).writeText(report)
                showOnceOpen(app, report)
            }
        }
        startFreezeWatchdog(app)
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

    private const val FREEZE_FILE = "pending_freeze.txt"

    /**
     * Opens the report once the app's own screen is up. Opening it straight away, while the app
     * is still starting, made the app close again on some boxes.
     */
    private fun showOnceOpen(app: Context, report: String) {
        val application = app as? android.app.Application ?: return
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity is CrashReportActivity) return
                application.unregisterActivityLifecycleCallbacks(this)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) runCatching {
                        activity.startActivity(
                            Intent(activity, CrashReportActivity::class.java).putExtra(EXTRA_REPORT, report)
                        )
                    }
                }, 2_000)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /** Opens the last saved crash or freeze report (developer tools in Live TV settings). */
    fun openLastReport(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java)
                    .putExtra(EXTRA_REPORT, lastReport(context) ?: "No crash or freeze has been saved yet.")
                    .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    /**
     * Notices when the app stops responding: every second it asks the main thread to answer;
     * if it hasn't for 4 seconds, what the main thread is stuck on is written down. If the app
     * recovers, the note is thrown away; if Android closes it while frozen, the note is shown
     * as a crash report next time the app opens.
     */
    private fun startFreezeWatchdog(app: Context) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val answeredAt = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.uptimeMillis())
            var askedAt = 0L
            var written = false
            var lastLoop = android.os.SystemClock.uptimeMillis()
            while (true) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val now = android.os.SystemClock.uptimeMillis()
                // The whole app was paused (in the background, device asleep): start over.
                if (now - lastLoop > 3_000) { answeredAt.set(now); askedAt = 0L }
                lastLoop = now
                if (askedAt == 0L || answeredAt.get() >= askedAt) {
                    if (written) { runCatching { File(app.filesDir, FREEZE_FILE).delete() }; written = false }
                    askedAt = now
                    main.post { answeredAt.set(android.os.SystemClock.uptimeMillis()) }
                } else if (now - askedAt > 4_000 && !written) {
                    written = true
                    val mainThread = android.os.Looper.getMainLooper().thread
                    val report = buildString {
                        appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine()
                        appendLine("APP FROZE (stopped responding for ${(now - askedAt) / 1000}s and was closed)")
                        appendLine("Main thread was stuck at:")
                        mainThread.stackTrace.take(40).forEach { appendLine("    at $it") }
                    }
                    runCatching { File(app.filesDir, FREEZE_FILE).writeText(report) }
                    Log.e(TAG, report)
                }
            }
        }, "FreezeWatchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
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
