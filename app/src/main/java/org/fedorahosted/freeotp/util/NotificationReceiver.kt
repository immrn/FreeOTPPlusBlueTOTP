package org.fedorahosted.freeotp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

private val TAG = "mrnNotificationReceiver"

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BleService.ACTION_CONFIRM_TOTP_REQUEST -> {
                Log.d(TAG, "received action confirm totp request")
                Log.d(TAG, "Notification ID: ${intent.extras!!.getInt(BleService.EXTRA_NOTIFY_ID)}")
                Log.d(TAG, "Username: ${intent.extras!!.getString(BleService.EXTRA_USERNAME)}")
                Log.d(TAG, "Domain: ${intent.extras!!.getString(BleService.EXTRA_DOMAIN)}")
                val confirmTotpReqIntent = Intent(BleService.ACTION_CONFIRM_TOTP_REQUEST).apply {
                    val extras = Bundle()
                    extras.putString(BleService.EXTRA_USERNAME, intent.extras!!.getString(BleService.EXTRA_USERNAME))
                    extras.putInt(BleService.EXTRA_NOTIFY_ID, intent.extras!!.getInt(BleService.EXTRA_NOTIFY_ID))
                    extras.putString(BleService.EXTRA_DOMAIN, intent.extras!!.getString(BleService.EXTRA_DOMAIN))
                    putExtras(extras)
                }
                LocalBroadcastManager.getInstance(BleService.applicationContext()).sendBroadcast(confirmTotpReqIntent)
            }

            BleService.ACTION_DENY_TOTP_REQUEST -> {
                val denyTotpReqIntent = Intent(BleService.ACTION_DENY_TOTP_REQUEST).apply{
                    putExtra(BleService.EXTRA_NOTIFY_ID, intent.extras!!.getInt(BleService.EXTRA_NOTIFY_ID))
                }
                LocalBroadcastManager.getInstance(BleService.applicationContext()).sendBroadcast(denyTotpReqIntent)
            }
        }
    }
}