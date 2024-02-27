/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Portions Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.databinding.MainBinding
import org.fedorahosted.freeotp.util.BleService
import org.fedorahosted.freeotp.util.Settings
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.math.max


private val TAG = "mrnMainActivity"
private val REQUEST_CODE_PERMISSIONS = 11
private var REQUIRED_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf()
    }

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var tokenMigrationUtil: MigrationUtil
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenListAdapter: TokenListAdapter
    private lateinit var bluetoothManager: BluetoothManager

    private var ble_permissions_denied = false
    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainBinding
    private var searchQuery = ""
    private var menu: Menu? = null
    private var lastSessionEndTimestamp = 0L;

    private var mMainActivityBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "received broadcast with action ${intent.action.toString()}")
            when (intent.action) {
                BleService.DISCONNECTED_ACTION -> {
                    showConnectionState(isConnected = false)
                }
                BleService.CONNECTED_ACTION -> {
                    showConnectionState(isConnected = true)
                }
            }
        }
    }

    private val tokenListObserver: AdapterDataObserver = object: AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            binding.tokenList.scrollToPosition(positionStart)
        }
    }

    private val dateFormatter : DateFormat = SimpleDateFormat("yyyyMMdd_HHmm")

    private var requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "granted access")
            Log.i(TAG, "starting advertising service")
            startService()
        }else{
            Log.i(TAG, "denied access")
            ble_permissions_denied = true
        }
    }

    fun startService() {
        Log.i(TAG, "starting BleService")
        val serviceIntent = Intent(this, BleService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    fun stopService() {
        Log.i(TAG, "stopping AdvertiseBleService")
        val serviceIntent = Intent(this, BleService::class.java)
        stopService(serviceIntent)
    }

    fun isServiceRunningInForeground(): Boolean {
        val manager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (BleService::class.java.name == service.service.className) {
                if (service.foreground) {
                    return true
                }
            }
        }
        return false
    }

    private fun permissionBtConnectGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PERMISSION_GRANTED
        } else {
            true // sdk version < S doesn't need the permission, so everything is good
        }
    }
    private fun permissionBtAdvertiseGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PERMISSION_GRANTED
        } else {
            true // sdk version < S doesn't need the permission, so everything is good
        }
    }
    private fun permissionPostNotificationGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED
        } else {
            true // sdk version < TIRAMISU doesn't need the permission, so everything is good
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!permissionBtConnectGranted() || !permissionBtAdvertiseGranted()) {
                Toast.makeText(this, R.string.ble_permissions_denied_text, Toast.LENGTH_LONG).show()
            } else if (permissionBtConnectGranted() && permissionBtAdvertiseGranted()) {
                activateBluetooth()
            }

            if (!permissionPostNotificationGranted()) {
                Toast.makeText(this, R.string.notify_permissions_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Bluetooth Low Energy related ----- //
        Log.i(TAG, "checking BLE support and asking for permissions")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        // We can't continue without proper Bluetooth support:
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            Log.i(TAG, "bluetooth not supported")
            finish()
        }

        // Check permissions and respectively request:
        if (permissionBtConnectGranted() && permissionBtAdvertiseGranted()) {
            activateBluetooth()
        }
        if (!permissionBtConnectGranted() || !permissionBtConnectGranted() || !permissionPostNotificationGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        onNewIntent(intent)

        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.migrateOldData()

        binding.tokenList.adapter = tokenListAdapter

        // Used GridlayoutManager to support tablet mode for multiple columns
        // Make sure one column has at least 320 DP
        val columns =  max(1, resources.configuration.screenWidthDp / 320)
        binding.tokenList.layoutManager = GridLayoutManager(this, columns)


        ItemTouchHelper(TokenTouchCallback(this, tokenListAdapter, otpTokenDatabase))
            .attachToRecyclerView(binding.tokenList)
        tokenListAdapter.registerAdapterDataObserver(tokenListObserver)

        lifecycleScope.launch {
            viewModel.getTokenList().collect { tokens ->
                tokenListAdapter.submitList(tokens)

                if (tokens.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.tokenList.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.tokenList.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.getAuthState().collect { authState ->
                if (authState == MainViewModel.AuthState.UNAUTHENTICATED) {
                    verifyAuthentication()
                }
            }
        }

        setSupportActionBar(binding.toolbar)

        binding.searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener, androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setTokenSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                searchQuery = query ?: ""
                viewModel.setTokenSearchQuery(query ?: "")
                return true
            }

        })

        binding.addTokenFab.setOnClickListener {
            if (BleService.isConnectedWithDevice() && BleService.extWaitsForQrScan) {
                startActivity(Intent(this, ScanTokenActivity::class.java))
            }
            else {
                startActivity(Intent(this, ConnectExtensionActivity::class.java))
            }
        }

        setBtButtonDrawable(enabled = true)
//        binding.bleOnOff.setBackgroundColor(Color.parseColor("#00000000"))
        binding.bleOnOff.setOnClickListener {
            val isOn = isServiceRunningInForeground()
            if (isOn) {
                stopService()
                Toast.makeText(this, R.string.stopped_ble_service, Toast.LENGTH_SHORT).show()
                setBtButtonDrawable(enabled = false)
            } else {
                startService()
                Toast.makeText(this, R.string.started_ble_service, Toast.LENGTH_SHORT).show()
                setBtButtonDrawable(enabled = true)
            }
        }

        // Show connection state:
        showConnectionState(isConnected = BleService.isConnectedWithDevice())
        val intentFilter = IntentFilter()
        intentFilter.addAction(BleService.CONNECTED_ACTION)
        intentFilter.addAction(BleService.DISCONNECTED_ACTION)
        LocalBroadcastManager.getInstance(this).registerReceiver(mMainActivityBroadcastReceiver, intentFilter)
    }

    private fun showConnectionState(isConnected: Boolean) {
        if (isConnected){
//            Toast.makeText(applicationContext, getString(R.string.connected), Toast.LENGTH_SHORT).show();
            binding.layoutConnectionState.setBackgroundColor(Color.parseColor("#8EAE01"))
            binding.imageViewConnectionState.setImageResource(R.drawable.connection_state_connected)
            binding.textViewConnectionState.setText(R.string.connected)
        } else {
//            Toast.makeText(applicationContext, getString(R.string.disconnected), Toast.LENGTH_SHORT).show();
            binding.layoutConnectionState.setBackgroundColor(Color.parseColor("#DC5812"))
            binding.imageViewConnectionState.setImageResource(R.drawable.connection_state_disconnected)
            binding.textViewConnectionState.setText(R.string.disconnected)
        }
    }

    private fun activateBluetooth() {
        Log.i(TAG, "asking for basic Bluetooth permission")
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        requestBluetooth.launch(enableBtIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMainActivityBroadcastReceiver)
        tokenListAdapter.unregisterAdapterDataObserver(tokenListObserver)
        lastSessionEndTimestamp = 0L;
    }

    override fun onStart() {
        super.onStart()

        viewModel.onSessionStart()
    }
    
    override fun onStop() {
        super.onStop()
        viewModel.onSessionStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        this.menu = menu
        refreshOptionMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Commented some because they break the concept of blue totp at the moment:
//            R.id.action_scan -> {
//                startActivity(Intent(this, ScanTokenActivity::class.java))
//                return true
//            }
//            R.id.action_add -> {
//                startActivity(Intent(this, AddActivity::class.java))
//                return true
//            }
//            R.id.action_import_json -> {
//                performFileSearch(READ_JSON_REQUEST_CODE)
//                return true
//            }
//            R.id.action_import_key_uri -> {
//                performFileSearch(READ_KEY_URI_REQUEST_CODE)
//                return true
//            }
//            R.id.action_export_json -> {
//                createFile("application/json", "freeotp-backup","json", WRITE_JSON_REQUEST_CODE)
//                return true
//            }
//            R.id.action_export_key_uri -> {
//                createFile("text/plain", "freeotp-backup","txt", WRITE_KEY_URI_REQUEST_CODE)
//                return true
//            }
            R.id.use_dark_theme -> {
                settings.darkMode = !settings.darkMode
                if (settings.darkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                recreate()
                return true
            }
            R.id.copy_to_clipboard -> {
                settings.copyToClipboard = !settings.copyToClipboard
                item.isChecked = settings.copyToClipboard
                refreshOptionMenu()
            }
//            R.id.require_authentication -> {
//                // Make sure we also verify authentication before turning on the settings
//
//                if (!settings.requireAuthentication) {
//                    viewModel.setAuthState(MainViewModel.AuthState.UNAUTHENTICATED)
//                } else {
//                    settings.requireAuthentication = false
//                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
//                    refreshOptionMenu()
//                }
//
//                return true
//            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (packageName == intent.extras?.getString(SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA)) {
            Log.i(TAG, "Intent shared from the same package name. Ignoring the intent and do not add the token")
            return
        }

        val uri = intent.data
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    otpTokenDatabase.otpTokenDao().insert(OtpTokenFactory.createFromUri(uri))
                } catch (e: Exception) {
                    Snackbar.make(binding.rootView, R.string.invalid_token_uri_received, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (resultCode != Activity.RESULT_OK) {
            return
        }

        when (requestCode) {
            WRITE_JSON_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportJsonFile(uri)
                    Snackbar.make(binding.rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }

            READ_JSON_REQUEST_CODE -> {
                val uri = resultData?.data ?: return
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.import_json_file)
                        .setMessage(R.string.import_json_file_warning)
                        .setIcon(R.drawable.alert)
                        .setPositiveButton(R.string.ok_text) { _: DialogInterface, _: Int ->
                            lifecycleScope.launch {
                                try {
                                    importFromUtil.importJsonFile(uri)
                                    Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                            .show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Import JSON failed", e)
                                    Snackbar.make(binding.root, R.string.import_json_failed_text, Snackbar.LENGTH_SHORT)
                                            .show()
                                }
                            }

                        }
                        .setNegativeButton(R.string.cancel_text, null)
                        .show()
            }

            WRITE_KEY_URI_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportKeyUriFile(uri)
                    Snackbar.make(binding.rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }

            READ_KEY_URI_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    try {
                        importFromUtil.importKeyUriFile(uri)
                        Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        Snackbar.make(binding.rootView, R.string.import_key_uri_failed_text, Snackbar.LENGTH_SHORT)
                                .show()
                    }
                }
            }
        }

    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"

        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext,
                    getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show();
        }
    }

    private fun createFile(mimeType: String, fileName: String, fileExtension: String, requestCode: Int, appendTimestamp: Boolean = true) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Create a file with the requested MIME type.
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_TITLE, "$fileName${if(appendTimestamp) "_${dateFormatter.format(Date())}" else ""}.$fileExtension")

        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext,
                    getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show();
        }
    }

    private fun refreshOptionMenu() {
        this.menu?.findItem(R.id.use_dark_theme)?.isChecked = settings.darkMode
        this.menu?.findItem(R.id.copy_to_clipboard)?.isChecked = settings.copyToClipboard
//        this.menu?.findItem(R.id.require_authentication)?.isChecked = settings.requireAuthentication
    }

    private fun verifyAuthentication() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int,
                                                       errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        // Don't show error message toast if user pressed back button
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            Toast.makeText(applicationContext,
                                "${getString(R.string.authentication_error)} $errString", Toast.LENGTH_SHORT)
                                .show()
                        }

                        if (errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                            finish()
                        }
                    }

                    override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)

                        if (!settings.requireAuthentication) {
                            settings.requireAuthentication = true
                            refreshOptionMenu()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // Invalid authentication, e.g. wrong fingerprint. Android auth UI shows an
                        // error, so no need for FreeOTP to show one
                        super.onAuthenticationFailed()

                        Toast.makeText(applicationContext,
                            R.string.unable_to_authenticate, Toast.LENGTH_SHORT)
                            .show()
                    }
                })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.authentication_dialog_title))
                .setSubtitle(getString(R.string.authentication_dialog_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()

        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        const val READ_JSON_REQUEST_CODE = 42
        const val WRITE_JSON_REQUEST_CODE = 43
        const val READ_KEY_URI_REQUEST_CODE = 44
        const val WRITE_KEY_URI_REQUEST_CODE = 45
        const val SCREENSHOT_MODE_EXTRA = "screenshot_mode"
        const val SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA = "shareFromPackageName"
    }

    private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported")
            return false
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported")
            return false
        }
        return true
    }

    private fun setBtButtonDrawable(enabled: Boolean) {
        if (enabled) {
            binding.bleOnOff.setImageResource(R.drawable.bt_icon_enabled)
            binding.bleOnOff.setColorFilter(Color.parseColor("#FFFFFF"))
        } else {
            binding.bleOnOff.setImageResource(R.drawable.bt_icon_disabled)
            if (isDarkmode()) {
                binding.bleOnOff.setColorFilter(Color.parseColor("#A0A0A0"))
            } else {
                binding.bleOnOff.setColorFilter(Color.parseColor("#303030"))
            }
        }
    }

    private fun isDarkmode(): Boolean {
        return this.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
