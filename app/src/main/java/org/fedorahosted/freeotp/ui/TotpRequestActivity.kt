package org.fedorahosted.freeotp.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.fedorahosted.freeotp.databinding.TotpRequestBinding
import org.fedorahosted.freeotp.util.BleService
import org.fedorahosted.freeotp.util.NotificationReceiver

class TotpRequestActivity : AppCompatActivity() {
    private lateinit var binding: TotpRequestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TotpRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get intent data:
        val notifyCounter = intent.extras!!.getInt(BleService.EXTRA_NOTIFY_ID)
        val username = intent.extras!!.getString(BleService.EXTRA_USERNAME)
        val domain = intent.extras!!.getString(BleService.EXTRA_DOMAIN)

        // Set domain and username:
        binding.totpReqDomain.text = domain
        binding.totpReqUsername.text = username

        // Set button listeners:
        binding.totpReqConfirm.setOnClickListener{
            val confirmIntent = Intent(applicationContext, NotificationReceiver::class.java).apply{
                action = BleService.ACTION_CONFIRM_TOTP_REQUEST
                val extras = Bundle()
                extras.putInt(BleService.EXTRA_NOTIFY_ID, notifyCounter)
                extras.putString(BleService.EXTRA_USERNAME, username)
                extras.putString(BleService.EXTRA_DOMAIN, domain)
                putExtras(extras)
            }
            LocalBroadcastManager.getInstance(BleService.applicationContext()).sendBroadcast(confirmIntent)
            finish()
        }
        binding.totpReqDeny.setOnClickListener{
            val denyIntent = Intent(applicationContext, NotificationReceiver::class.java).apply{
                action = BleService.ACTION_DENY_TOTP_REQUEST
                putExtra(BleService.EXTRA_NOTIFY_ID, notifyCounter)
            }
            LocalBroadcastManager.getInstance(BleService.applicationContext()).sendBroadcast(denyIntent)
            finish()
        }
    }
}