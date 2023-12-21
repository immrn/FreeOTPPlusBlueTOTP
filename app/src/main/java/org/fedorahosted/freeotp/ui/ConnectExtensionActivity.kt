package org.fedorahosted.freeotp.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import org.fedorahosted.freeotp.databinding.ConnectExtensionBinding
import org.fedorahosted.freeotp.util.BleService

class ConnectExtensionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding: ConnectExtensionBinding = ConnectExtensionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mBluetoothManager =  getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val stepsToShow = mutableListOf(
                Pair(binding.extNum1, binding.extStep1),
                Pair(binding.extNum2, binding.extStep2),
                Pair(binding.extNum3, binding.extStep3),
                Pair(binding.extNum4, binding.extStep4),
                Pair(binding.extNum5, binding.extStep5)
        )
        stepsToShow.forEach {
            it.first.visibility = View.GONE
            it.second.visibility = View.GONE
        }

        if (mBluetoothManager.adapter.isEnabled) {
            stepsToShow.removeAt(0) // rm step 1
//            binding.extStep1.visibility = View.GONE
//            binding.extNum1.visibility = View.GONE
        }
        if (BleService.isConnectedWithDevice()) {
            stepsToShow.removeAt(0) // rm step 2
            stepsToShow.removeAt(0) // rm step 3
//            binding.extStep2.visibility = View.GONE
//            binding.extNum2.visibility = View.GONE
//            binding.extStep3.visibility = View.GONE
//            binding.extNum3.visibility = View.GONE
        }
        stepsToShow.forEachIndexed{ idx, s ->
            val num = (idx + 1).toString()
            s.first.text = "$num."
            s.first.visibility = View.VISIBLE
            s.second.visibility = View.VISIBLE
        }

        binding.extOkButton.setOnClickListener{
            finish()
        }
    }
}
