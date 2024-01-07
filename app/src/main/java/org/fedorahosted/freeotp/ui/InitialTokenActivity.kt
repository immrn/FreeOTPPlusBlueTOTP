package org.fedorahosted.freeotp.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.databinding.InitialTokenBinding
import org.fedorahosted.freeotp.util.BleService
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds


private val TAG = "mrnInitialTokenActivity"

@AndroidEntryPoint
class InitialTokenActivity : ComponentActivity() {
    @Inject lateinit var tokenCodeUtil: TokenCodeUtil
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var binding: InitialTokenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = InitialTokenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            Log.i(TAG, "curr domain: ${BleService.curr_setup_domain}")
            Log.i(TAG, "curr username: ${BleService.curr_setup_username}")
            val token = otpTokenDatabase.otpTokenDao().getByDomainAndUsername(BleService.curr_setup_domain, BleService.curr_setup_username).first()
            if (token == null) {
                Log.e(TAG, "Something went wrong while trying to get the token out of the database")
                return@launch
            }

//                // Copy code to clipboard. TODO add a copy button that runs this:
//                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, codes.currentCode))
//                Snackbar.make(v, R.string.code_copied, Snackbar.LENGTH_SHORT).show()

            var totps: TokenCode? = null

            // calculate the totp
            launch {
                while (isActive) {
                    totps = tokenCodeUtil.generateTokenCode(token)
                    if (token.tokenType == OtpTokenType.HOTP) {
                        otpTokenDatabase.otpTokenDao().incrementCounter(token.id)
                    }

                    binding.inittoken.text = totps!!.currentCode!!
                    val remainingSec = 30 - (System.currentTimeMillis() / 1000).toLong() % 30
                    binding.initTokenSeconds.text = remainingSec.toString()
                    delay(remainingSec * 1000)
                }
            }

            // run the progress bar
            launch {
                while (isActive) {
                    if (totps != null) {
                        val remainingSec = 30 - (System.currentTimeMillis() / 1000).toLong() % 30
                        binding.initTokenSeconds.text = remainingSec.toString()
                        binding.initTokenProgress.progress = 1000 - totps!!.currentProgress
                        Log.i(TAG, "progress")
                        delay(50)
                    }
                }
            }
        }

        binding.okButton.setOnClickListener{
            startActivity(Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
