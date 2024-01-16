package org.fedorahosted.freeotp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.util.Log
import org.fedorahosted.freeotp.ui.ShowTokenActivity

private val TAG = "mrnCommonReceiver"

class CommonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "received ${intent.action!!}")
        when(intent.action) {
            BleService.ACTION_RECEIVED_SETUP_DATA -> {
                val i = Intent(context, ShowTokenActivity::class.java).apply{
                    flags = FLAG_ACTIVITY_NEW_TASK
                    action = ShowTokenActivity.ACTION_SETUP
                    putExtra(ShowTokenActivity.EXTRA_DOMAIN, intent.extras!!.getString(ShowTokenActivity.EXTRA_DOMAIN))
                    putExtra(ShowTokenActivity.EXTRA_USERNAME, intent.extras!!.getString(ShowTokenActivity.EXTRA_USERNAME))
                }
                context.startActivity(i)
            }
        }
    }
}