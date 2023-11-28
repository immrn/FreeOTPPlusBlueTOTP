package org.fedorahosted.freeotp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.OnboardingBinding

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show onboarding activity if user didn't check the checkbox "Don't show onboarding anymore" at the onboarding screen:
        val sharedPref = getApplicationContext().getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val defaultValue = resources.getBoolean(R.bool.show_onb_default_key)
        val show_onboarding_activity = sharedPref.getBoolean(getString(R.string.show_onb_key), defaultValue)
        if (!show_onboarding_activity) {
            Log.i("mrndebug", "skip onboarding activity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

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
