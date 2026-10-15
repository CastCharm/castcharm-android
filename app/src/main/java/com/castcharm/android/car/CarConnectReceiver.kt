package com.castcharm.android.car

// Fires when any Bluetooth device connects (a car head unit included). See
// CarWarmStart for why. Delivered to this app even when it is not running;
// on Android 12+ only with the Bluetooth permission, which Settings asks for.

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.castcharm.android.AA_WARM_START_KEY
import com.castcharm.android.dataStore
import com.castcharm.android.diag.DiagLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class CarConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" ->
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
            else -> false
        }
        if (!connected) return
        val enabled = runCatching {
            runBlocking { context.applicationContext.dataStore.data.map { it[AA_WARM_START_KEY] ?: true }.first() }
        }.getOrDefault(true)
        val name = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name
        }.getOrNull() ?: "unknown device"
        DiagLog.log("car", "bluetooth connected: $name (${action.substringAfterLast('.')}) warmStart=${if (enabled) "on" else "off"}")
        if (enabled) CarWarmStart.hold(context, "bluetooth: $name")
    }
}
