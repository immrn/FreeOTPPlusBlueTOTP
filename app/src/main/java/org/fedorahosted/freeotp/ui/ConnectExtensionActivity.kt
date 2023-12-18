package org.fedorahosted.freeotp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.fedorahosted.freeotp.databinding.ConnectExtensionBinding
import org.fedorahosted.freeotp.util.BleService

class ConnectExtensionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding: ConnectExtensionBinding = ConnectExtensionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.extOkButton.setOnClickListener{
            if (BleService.isConnectedWithDevice() && BleService.extWaitsForQrScan) {
                startActivity(Intent(this, ScanTokenActivity::class.java))
            } else {
                finish()
            }
        }
    }
}
