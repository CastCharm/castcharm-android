package com.castcharm.android.diag

// A small, persistent diary of playback and Android Auto decisions. Android's
// own log buffer rotates within hours, which is how a wrong-episode start in
// the car kept escaping diagnosis. This keeps the last few megabytes on the
// phone, never sends them anywhere, and is shown under Settings → Diagnostics.
//
// Size is capped at 10 MB: the live file is rotated to a single backup once it
// passes 5 MB, and the backup is overwritten on the next rotation.

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagLog {
    private const val FILE = "diagnostics.log"
    private const val BACKUP = "diagnostics.1.log"
    private const val ROTATE_AT = 5L * 1024 * 1024          // × 2 files = 10 MB ceiling
    private const val DEFAULT_TAIL_CHARS = 200_000

    @Volatile private var dir: File? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "diaglog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    fun log(tag: String, message: String) {
        Log.d("CastCharm", "[$tag] $message")
        val d = dir ?: return
        val line = "${fmt.format(Date())} [$tag] $message\n"
        executor.execute {
            try {
                val f = File(d, FILE)
                if (f.exists() && f.length() > ROTATE_AT) {
                    val b = File(d, BACKUP)
                    if (b.exists()) b.delete()
                    f.renameTo(b)
                }
                f.appendText(line)
            } catch (_: Exception) {
                // A diary that cannot be written is not worth crashing over.
            }
        }
    }

    /** The newest [maxChars] of the log, oldest line first. */
    fun tail(maxChars: Int = DEFAULT_TAIL_CHARS): String {
        val d = dir ?: return ""
        val parts = StringBuilder()
        for (name in listOf(BACKUP, FILE)) {
            val f = File(d, name)
            if (!f.exists()) continue
            try {
                RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length()
                    val start = maxOf(0L, len - maxChars)
                    raf.seek(start)
                    val bytes = ByteArray((len - start).toInt())
                    raf.readFully(bytes)
                    parts.append(String(bytes, Charsets.UTF_8))
                }
            } catch (_: Exception) { }
        }
        val s = parts.toString()
        if (s.length <= maxChars) return s
        val cut = s.length - maxChars
        val nl = s.indexOf('\n', cut)
        return if (nl >= 0) s.substring(nl + 1) else s.substring(cut)
    }

    fun sizeBytes(): Long {
        val d = dir ?: return 0
        return listOf(FILE, BACKUP).sumOf { File(d, it).takeIf { f -> f.exists() }?.length() ?: 0L }
    }

    fun clear() {
        val d = dir ?: return
        executor.execute { listOf(FILE, BACKUP).forEach { File(d, it).delete() } }
    }
}
