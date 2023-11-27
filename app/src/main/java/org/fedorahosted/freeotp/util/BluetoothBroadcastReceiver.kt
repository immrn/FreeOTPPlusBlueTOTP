package org.fedorahosted.freeotp.util

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BluetoothBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    // Bluetooth is turned on, start your background process or service
                    Log.e("mrndebug", "bt turned on")
                    startBackgroundProcess(context)
                }
                BluetoothAdapter.STATE_OFF -> {
                    // Bluetooth is turned off, handle accordingly
                }
            }
        }
    }

    private fun startBackgroundProcess(context: Context) {
        // Start your background process or service here
        val serviceIntent = Intent(context, AdvertiseBleService::class.java)
        context.startService(serviceIntent)
    }
}