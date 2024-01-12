package org.fedorahosted.freeotp.util

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

private val TAG = "mrnBleBroadcastReceiver"

class BleBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.e(TAG, "received $intent \n${intent.extras}")
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "Bt turned off. Advertising was stopped.")
                        // Bluetooth adapter stops advertising automatically
                    }

                    BluetoothAdapter.STATE_TURNING_OFF -> {}
                    BluetoothAdapter.STATE_TURNING_ON -> {}
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bt turned on. Sending broadcast to start advertising.")
                        val advIntent = Intent(BleService.START_ADVERTISING_ACTION)
                        LocalBroadcastManager.getInstance(BleService.applicationContext()).sendBroadcast(advIntent)
                    }
                }
            }
        }
    }
}