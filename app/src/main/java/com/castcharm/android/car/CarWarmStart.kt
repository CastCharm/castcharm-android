package com.castcharm.android.car

// Keeps the media service alive for a few minutes after a Bluetooth device
// connects. Android Auto lists a sideloaded media app only after its
// validator has bound to the app's browser service, and when the app's
// process is dead at the moment the car connects that bind often never
// comes — the app is simply missing from the launcher until a retry minutes
// later. The car's Bluetooth link comes up a second or two before Android
// Auto starts, and a Bluetooth connection is one of the few events the
// system still hands to an app that is not running, so it is the hook: bind
// the service (no notification, no playback), let the validator find a live
// session, and let go once the car has had its chance.

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.castcharm.android.diag.DiagLog
import com.castcharm.android.player.PlayerService

object CarWarmStart {
    private const val HOLD_MS = 3 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var appContext: Context? = null

    @Synchronized
    fun hold(context: Context, reason: String) {
        val app = context.applicationContext
        appContext = app
        handler.removeCallbacks(releaseRunnable)
        if (connection == null) {
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    DiagLog.log("car", "service warm and bound ($reason)")
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            val ok = runCatching {
                app.bindService(Intent(app, PlayerService::class.java), conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            DiagLog.log("car", "warm start ${if (ok) "requested" else "FAILED"}: $reason; holding for ${HOLD_MS / 1000}s")
            if (ok) connection = conn
        } else {
            DiagLog.log("car", "warm start already held; extended ($reason)")
        }
        handler.postDelayed(releaseRunnable, HOLD_MS)
    }

    private val releaseRunnable = Runnable { release("hold expired") }

    @Synchronized
    fun release(reason: String) {
        val conn = connection ?: return
        connection = null
        runCatching { appContext?.unbindService(conn) }
        DiagLog.log("car", "warm start released ($reason)")
    }
}
