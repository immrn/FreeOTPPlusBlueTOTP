package org.fedorahosted.freeotp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.databinding.ShowTokenBinding
import org.fedorahosted.freeotp.util.BleService
import javax.inject.Inject


private val TAG = "mrnShowTokenActivity"

@AndroidEntryPoint
class ShowTokenActivity : ComponentActivity() {
    @Inject lateinit var tokenCodeUtil: TokenCodeUtil
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase

    companion object {
        const val ACTION_SHOW_WITH_WARNING = "ACTION_SHOW_WITH_WARNING"
        const val ACTION_SETUP = "ACTION_SETUP"
        const val EXTRA_TOKEN_ID = "token_id"
        const val EXTRA_DOMAIN = "org.fedorahosted.freeotp.EXTRA_DOMAIN"
        const val EXTRA_USERNAME = "org.fedorahosted.freeotp.EXTRA_USERNAME"
    }

    private lateinit var binding: ShowTokenBinding
    private lateinit var clipboardManager: ClipboardManager
    private var domain: String = ""
    private var username: String = ""
    private var tokenId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ShowTokenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO add a copy button that runs this as well:
        clipboardManager = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        binding.inittoken.setOnClickListener{
            clipboardManager.setPrimaryClip(ClipData.newPlainText(null, binding.inittoken.text))
            Snackbar.make(binding.inittoken, R.string.code_copied, Snackbar.LENGTH_SHORT).show()
        }

        Log.i(TAG, "intent: ${intent}")
        for (e in intent.extras!!.keySet()) {
            Log.i(TAG, "$e: ${intent.extras!!.get(e)}")
        }

        // Determine source to identify the token:
        if (intent.extras!!.containsKey(EXTRA_TOKEN_ID)) {
            tokenId = intent.extras!!.getLong(EXTRA_TOKEN_ID)
        } else {
            domain = intent.extras!!.getString(EXTRA_DOMAIN)!!
            username = intent.extras!!.getString(EXTRA_USERNAME)!!
        }
        Log.i(TAG, "domain: $domain; username: $username; tokenId: $tokenId")

        lifecycleScope.launch {
            Log.i(TAG, "curr domain: $domain")
            Log.i(TAG, "curr username: $username")

            val token: OtpToken? = if (tokenId == null) {
                otpTokenDatabase.otpTokenDao().getByDomainAndUsername(domain, username).first()
            } else {
                otpTokenDatabase.otpTokenDao().get(tokenId!!).first()
            }

            if (token == null) {
                Log.e(TAG, "Something went wrong while trying to get the token out of the database")
                return@launch
            }

            // Show warning instead of setup explanation:
            if (intent.action == ACTION_SHOW_WITH_WARNING) {
                binding.tokenExplain.text = getString(R.string.init_token_warning) + "\n\n" + token.domain!!
            }

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
                        binding.initTokenProgress.progress = totps!!.currentProgress
                        delay(50)
                    }
                }
            }
        }

        binding.okButton.setOnClickListener{
            startActivity(Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (intent.action == ACTION_SETUP) {
                BleService.sendBle(mapOf("key" to "setup_complete"))
            }
        }
    }
}
