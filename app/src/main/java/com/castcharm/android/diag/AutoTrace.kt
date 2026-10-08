package com.castcharm.android.diag

// AutoTrace records the handful of lifecycle moments that decide whether Android
// Auto can see CastCharm at all, to a file that survives the drive.
//
// Why a file and not just Log.d: the failures this exists for happen in a car,
// are noticed minutes later, and are not reproducible on a desk. logcat's ring
// buffer is a few MB shared by the whole device, so by the time the phone is back
// at a computer the relevant lines are long gone. Everything here is also logged
// to logcat as usual; this is the copy that is still there tomorrow.
//
// Kept deliberately tiny. It answers one question — how far did the attach get? —
// and the interesting answers are the ones where a line is *missing*:
//
//   onBind action=android.media.browse.MediaBrowserService ...
//
// is Android Auto asking for the browse binder. No such line means Gearhead never
// reached us and the problem is outside the app. A line with session=false means
// the session build failed and Auto was handed nothing, which is sticky for the
// life of the service instance (see PlayerService.onBind).
//
// Pull it with:
//   adb pull /sdcard/Android/data/com.castcharm.android/files/auto-trace.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "CastCharm"
private const val FILE_NAME = "auto-trace.log"

// Rotate well before the file could matter to a user's storage. Half is kept on
// rotate rather than the whole thing being dropped, because the run-up to a
// failure is exactly what gets lost by truncating to nothing.
private const val MAX_BYTES = 128 * 1024L

object AutoTrace {

    // Single thread: keeps writes ordered and off whichever thread is reporting.
    // Several call sites are the main thread during service startup and binder
    // threads during a browse, and neither should ever wait on the flash.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AutoTrace").apply { isDaemon = true }
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** Called once from Application.onCreate(); before this, entries are logcat-only. */
    fun init(context: Context) {
        if (logFile != null) return
        // getExternalFilesDir is app-private but adb-readable without root or a
        // debuggable build, which is the whole point — these builds are installed
        // as release APKs on the user's daily-driver phone.
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return
        logFile = File(dir, FILE_NAME)
    }

    fun log(message: String) {
        Log.d(TAG, message)
        val file = logFile ?: return
        val line = "${stamp.format(Date())} $message\n"
        io.execute {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    val kept = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText(kept.substringAfter('\n', kept))
                }
                file.appendText(line)
            }
        }
    }
}
