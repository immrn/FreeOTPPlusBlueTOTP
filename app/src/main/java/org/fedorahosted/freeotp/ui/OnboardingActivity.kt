package org.fedorahosted.freeotp.ui

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.OnboardingBinding

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding: OnboardingBinding = OnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.onbCheckbox.setOnCheckedChangeListener { _, isChecked ->
            val sharedPref = getApplicationContext().getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putBoolean(getString(R.string.show_onb_key), !isChecked)
                apply()
            }
        }

        binding.onbOkButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
