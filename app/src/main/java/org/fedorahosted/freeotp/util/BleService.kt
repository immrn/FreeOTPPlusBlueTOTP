package org.fedorahosted.freeotp.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.DEFAULT_SOUND
import androidx.core.app.NotificationCompat.DEFAULT_VIBRATE
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.ui.MainActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.ShowTokenActivity
import org.fedorahosted.freeotp.ui.TotpRequestActivity


private val TAG = "mrnBleService"

@AndroidEntryPoint
class BleService : Service () {
    init { val instance = this }

    private val CHANNEL_ID = "AdvertiseBleServiceChannel"
    private val CHANNEL_ID_TOTP_REQ = "TotpRequestChannel"
    companion object {
        const val START_ADVERTISING_ACTION = "org.fedorahosted.freeotp.BLE_SERVICE_START_ADVERTISING"
        const val CONNECTED_ACTION = "org.fedorahosted.freeotp.BLE_SERVICE_CONNECTED"
        const val DISCONNECTED_ACTION = "org.fedorahosted.freeotp.BLE_SERVICE_DISCONNECTED"
        const val ACTION_CONFIRM_TOTP_REQUEST = "org.fedorahosted.freeotp.CONFIRM_TOTP_REQUEST"
        const val ACTION_DENY_TOTP_REQUEST = "org.fedorahosted.freeotp.DENY_TOTP_REQUEST"
        const val ACTION_RECEIVED_SETUP_DATA = "org.fedorahosted.freeotp.RECEIVED_SETUP_DATA"
        const val EXTRA_NOTIFY_ID = "org.fedorahosted.freeotp.EXTRA_NOTIFY_ID"
        const val EXTRA_DOMAIN = "org.fedorahosted.freeotp.EXTRA_DOMAIN"
        const val EXTRA_USERNAME = "org.fedorahosted.freeotp.EXTRA_USERNAME"
        private var mBluetoothManager: BluetoothManager? = null
        private var mBleDevice: BluetoothDevice? = null
        private lateinit var mTxCharacteristic: BluetoothGattCharacteristic
        private var mBluetoothGattServer: BluetoothGattServer? = null

        var extWaitsForQrScan: Boolean = false
        var currSetupDomain: String = ""
        var currSetupUsername: String = ""

        @SuppressLint("MissingPermission")
        fun isConnectedWithDevice(): Boolean {
            return mBluetoothManager?.getConnectionState(mBleDevice, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        }

        var instance: BleService? = null
        fun applicationContext(): Context {
            return instance!!.applicationContext
        }

        @SuppressLint("MissingPermission")
        fun sendBle(message: Map<String, String>) {
            val messageStr = message.toString()
                    .replace("=", "\": \"")
                    .replace("{", "{\"")
                    .replace(", ", "\", \"")
                    .replace("}", "\"}")
            mTxCharacteristic.let {
                it.value = messageStr.toByteArray(Charsets.UTF_8)
                Log.i(TAG, "sending notify: $message")
                mBluetoothGattServer?.notifyCharacteristicChanged(mBleDevice, it, true)
            }
        }
    }

    // Notify when an extension asks for an totp: // TODO make both private
    private lateinit var notificationManager: NotificationManager
    private lateinit var foregroundNotifyManager: NotificationManager // for foreground service itself
    private lateinit var notifyBuilder: NotificationCompat.Builder
    private var notifyCounter: Int = 0
    private var pendingIntentRequestCodeCounter: Int = 0

    private val mBleBroadcastReceiver: BleBroadcastReceiver = BleBroadcastReceiver()
    private var mBleServiceBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "received broadcast with action ${intent.action.toString()}")
            when (intent.action) {
                START_ADVERTISING_ACTION -> {
                    mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback)
                    shortenBtAdapterName()
                    mBluetoothLeAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback)
                }
                ACTION_CONFIRM_TOTP_REQUEST -> {
                    val notifyId = intent.extras!!.getInt(EXTRA_NOTIFY_ID)
                    Log.d(TAG, "Notification ID: ${intent.extras!!.getInt(EXTRA_NOTIFY_ID)}")
                    val username = intent.getStringExtra(EXTRA_USERNAME)!!
                    Log.i(TAG, "username received: $username")
                    val domain = intent.getStringExtra(EXTRA_DOMAIN)!!
                    Log.i(TAG, "domain received: $domain")
                    notificationManager.cancel(notifyId)
                    scope.launch {
                        val token = otpTokenDatabase.otpTokenDao().getByDomainAndUsername(domain, username).first()
                        if (token == null) {
                            sendBle(mapOf(
                                    "key" to "response_totp",
                                    "totp" to "null"
                            ))
                            return@launch
                        }
                        val totp = tokenCodeUtil.generateTokenCode(token).currentCode!!
                        sendBle(mapOf(
                                "key" to "response_totp",
                                "totp" to totp
                        ))
                    }
                }
                ACTION_DENY_TOTP_REQUEST -> {
                    val notifyId = intent.extras!!.getInt(EXTRA_NOTIFY_ID)
                    notificationManager.cancel(notifyId)
                }
            }
        }
    }

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCodeUtil: TokenCodeUtil

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var mBluetoothLeAdvertiser: BluetoothLeAdvertiser
    private val mServiceUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f852")
    private val mTxCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f853")
    // This descriptor uuid is given by the BLE spec. This tx characteristic may notify the central device, therefore we need this descriptor:
    private val mCccDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val mRxCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f854")
    private val mAdvertiseSettings: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
    private val mAdvertiseData: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(mServiceUuid))
            .build()

    @SuppressLint("MissingPermission")
    private val mGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: $device")
                if (mBleDevice == null) {
                    mBleDevice = device
                }
                mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback)
                val intent = Intent(CONNECTED_ACTION)
                LocalBroadcastManager.getInstance(applicationContext()).sendBroadcast(intent)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: $device")
                extWaitsForQrScan = false
                // TODO cancel all? notifications here cancelAll()
                val intent = Intent(DISCONNECTED_ACTION)
                LocalBroadcastManager.getInstance(applicationContext()).sendBroadcast(intent)
                mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback)
                mBluetoothLeAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            // super.onNotificationSent(device, status)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            Log.i(TAG, "read request")
            if (characteristic.uuid == mTxCharUuid) {
                Log.i(TAG, "onChar read request, returning GATT_SUCCESS and null")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            else {
                // This if statement is unnecessary, we only need the "else" stuff.
                // Because the central device will only write, or it will be notified. It will never read.
                Log.i(TAG, "onChar read request, returning GATT_FAILURE and null, " +
                        "due unknown uuid")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            // super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val strValue = value?.toString(Charsets.UTF_8) ?: ""
            Log.i(TAG, "received msg: $strValue")
            if (characteristic != null && characteristic.uuid == mRxCharUuid) {
                handleIncomingMessage(strValue)
                if (responseNeeded) {
                    mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, strValue.toByteArray(Charsets.UTF_8))
                }
            } else {
                if (responseNeeded) {
                    Log.i(TAG, "responded with GATT_FAILURE")
                    mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                } else {
                    Log.i(TAG, "no response needed")
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            Log.i(TAG, "descriptor read request")
            // super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            if (descriptor != null && descriptor.uuid == mCccDescriptorUuid) {
                Log.i(TAG, "responding with GATT_SUCCESS and ENABLE_NOTIFICATION_VALUE")
                val ret = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ret)
            } else {
                Log.i(TAG, "responding with GATT_FAILURE, due unknown uuid")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
//            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            Log.i(TAG, "descriptor write request")
            if (descriptor != null && descriptor.uuid == mCccDescriptorUuid) {
                var status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                if (descriptor.characteristic.uuid == mTxCharUuid) {
                    if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        status = BluetoothGatt.GATT_SUCCESS
                    } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        status = BluetoothGatt.GATT_SUCCESS
                    }
                }
                if (responseNeeded) {
                    Log.i(TAG, "responding with $status")
                    mBluetoothGattServer?.sendResponse(device, requestId, status, 0, null)
                }
            } else {
                Log.i(TAG, "responding with GATT_FAILURE due unknown uuid")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    private val mAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "LE Advertise Started.")
        }

        @SuppressLint("MissingPermission")
        override fun onStartFailure(errorCode: Int) {
            var errorMsg: String = "unknown error"
            when (errorCode) {
                1 -> {
                    errorMsg = "Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes."
                    scope.launch{
                        Log.i(TAG, "shortening BT adapter name")
                        shortenBtAdapterName()
                        delay(4000) // Wait for adapter to change the bt adapter name
                        Log.i(TAG, "shortened Bluetooth adapter name: ${mBluetoothManager?.adapter?.name}")
                        // calling startAdvertising() here with this (callback) to restart it automatically, ends in a recursive IDE error, so use broadcast receiver:
                        val advIntent = Intent(START_ADVERTISING_ACTION)
                        LocalBroadcastManager.getInstance(applicationContext()).sendBroadcast(advIntent)
                    }
                }
                2 -> errorMsg = "Failed to start advertising because no advertising instance is available."
                3 -> errorMsg = "Failed to start advertising as the advertising is already started"
                4 -> errorMsg = "Operation failed due to an internal error."
                5 -> errorMsg = "This feature is not supported on this platform."
            }
            Log.e(TAG, "LE Advertise Failed (name: ${mBluetoothManager?.adapter?.name}): $errorMsg")
        }
    }

    private fun handleIncomingMessage(message: String) {
        val msg: Map<String, *>
        try {
            msg = JSONObject(message).toMap()
            Log.d(TAG, "parsed to map: $msg")
            when (msg["key"]) {
                "await_qr_scan" -> {
                    extWaitsForQrScan = true
                }
                "dont_await_qr_scan" -> {
                    extWaitsForQrScan = false
                }
                "response_setup_domain_username" -> {
                    if (extWaitsForQrScan) {
                        extWaitsForQrScan = false // finished scan
                        currSetupDomain = msg["domain"].toString()
                        currSetupUsername = msg["username"].toString()
                        Log.i(TAG, "set BleService.currSetupDomain: ${currSetupDomain}")
                        Log.i(TAG, "set BleService.currSetupUsername: ${currSetupUsername}")
                        scope.launch {
                            val token = otpTokenDatabase.otpTokenDao().getLatest().first() ?: return@launch
                            val newToken = token.copy(
                                domain = currSetupDomain,
                                username = currSetupUsername
                            )
                            otpTokenDatabase.otpTokenDao().update(newToken)
                            // User sees ScanTokenActivity now, we inform it, that we received the setup data (domain and username):
                            val i = Intent(applicationContext, CommonReceiver::class.java).apply{
                                action = ACTION_RECEIVED_SETUP_DATA
                                putExtra(ShowTokenActivity.EXTRA_DOMAIN, currSetupDomain)
                                putExtra(ShowTokenActivity.EXTRA_USERNAME, currSetupUsername)
                            }
                            sendBroadcast(i)
                            return@launch
                        }
                    }
                }
                "request_totp" -> {
                    val domain = msg["domain"].toString()
                    val username = msg["username"].toString()
                    scope.launch {
                        val token = otpTokenDatabase.otpTokenDao().getByDomainAndUsername(domain, username).first()
                        if (token == null) {
                            sendBle(mapOf(
                                    "key" to "response_totp",
                                    "totp" to "null"
                            ))
                            return@launch
                        }

                        // We have a token, so inform extension that user needs to confim the totp request
                        sendBle(mapOf("key" to "response_totp_await_user_confirm"))

                        val confirmIntent = Intent(applicationContext, NotificationReceiver::class.java).apply {
                            action = ACTION_CONFIRM_TOTP_REQUEST
                            val extras = Bundle()
                            Log.d(TAG, "Building confirm intent")
                            Log.i(TAG, "set ID: $notifyCounter")
                            Log.i(TAG, "set username: $username")
                            Log.i(TAG, "set domain: $domain")
                            extras.putInt(EXTRA_NOTIFY_ID, notifyCounter)
                            extras.putString(EXTRA_USERNAME, username)
                            extras.putString(EXTRA_DOMAIN, domain)
                            putExtras(extras)
                            Log.i(TAG, "this confirmIntent: $this")
                            Log.i(TAG, "confirmIntent extras: ${this.extras}")
                        }
                        val confirmPendingIntent: PendingIntent =
                                PendingIntent.getBroadcast(applicationContext, pendingIntentRequestCodeCounter, confirmIntent, FLAG_MUTABLE or FLAG_ONE_SHOT)
                        pendingIntentRequestCodeCounter += 1
                        val denyIntent = Intent(applicationContext, NotificationReceiver::class.java).apply {
                            action = ACTION_DENY_TOTP_REQUEST
                            putExtra(EXTRA_NOTIFY_ID, notifyCounter)
                        }
                        val denyPendingIntent: PendingIntent =
                                PendingIntent.getBroadcast(applicationContext, pendingIntentRequestCodeCounter, denyIntent, FLAG_MUTABLE or FLAG_ONE_SHOT)
                        pendingIntentRequestCodeCounter += 1
                        val notifyIntent = Intent(applicationContext, TotpRequestActivity::class.java).apply {
                            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            val extras = Bundle()
                            extras.putInt(EXTRA_NOTIFY_ID, notifyCounter)
                            extras.putString(EXTRA_USERNAME, username)
                            extras.putString(EXTRA_DOMAIN, domain)
                            putExtras(extras)
                        }
                        val notifyPendingIntent: PendingIntent = PendingIntent.getActivity(applicationContext, pendingIntentRequestCodeCounter, notifyIntent, FLAG_IMMUTABLE or FLAG_ONE_SHOT)
                        pendingIntentRequestCodeCounter += 1
                        val publicNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID_TOTP_REQ)
                                .setSmallIcon(R.drawable.ic_launcher_foreground_large)
                                .setContentTitle(getString(R.string.notify_totp_req_title_public) + " $domain?")
                        notifyBuilder = notifyBuilder
                                .setContentText(getString(R.string.notify_totp_req_text) + " " + domain)
                                .clearActions()
                                .addAction(R.drawable.bt_icon_enabled, getString(R.string.notify_totp_req_confim), confirmPendingIntent)
                                .addAction(R.drawable.bt_icon_disabled, getString(R.string.notify_totp_req_deny), denyPendingIntent)
                                .setContentIntent(notifyPendingIntent)
                                .setPublicVersion(publicNotification.build())

                        with (NotificationManagerCompat.from(applicationContext())) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val permissionStatus = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
                                if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                                    this.notify(notifyCounter, notifyBuilder.build())
                                    notifyCounter += 1
                                }
                            } else {
                                this.notify(notifyCounter, notifyBuilder.build())
                                notifyCounter += 1
                            }
                        }
                        // NotificationReceiver will receive the intent (confirm, deny) and then
                        // send a broadcast to the local broadcastReceiver here. It will send the
                        // ble message containing the totp if the user confirmed the totp request.
                        return@launch
                    }
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, e.stackTraceToString())
        }

    }

    private fun JSONObject.toMap(): Map<String, *> = keys().asSequence().associateWith {
        when (val value = this[it]) {
            is JSONArray -> {
                val map = (0 until value.length()).associate { Pair(it.toString(), value[it]) }
                JSONObject(map).toMap().values.toList()
            }
            is JSONObject -> value.toMap()
            JSONObject.NULL -> null
            else            -> value
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBleGattServer() {
        if (isConnectedWithDevice()) {
            Log.i(TAG, "GATT server is already connect to ${mBleDevice?.name}.\nNo need to init the gatt server again.")
            return
        }

        Log.i(TAG, "Initializing the GATT server")
        mBluetoothManager =  getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothLeAdvertiser = mBluetoothManager!!.adapter.bluetoothLeAdvertiser
        mBluetoothGattServer = mBluetoothManager!!.openGattServer(this, mGattServerCallback)
        mBluetoothManager!!.adapter.name = mBluetoothManager!!.adapter.name.replace(" ", "").take(8)
        Log.i(TAG, "Bluetooth adapter name: ${mBluetoothManager!!.adapter.name}")

        val rxTxService = BluetoothGattService(
                mServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rxCharacteristic = BluetoothGattCharacteristic(
                mRxCharUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
        mTxCharacteristic = BluetoothGattCharacteristic(
                mTxCharUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
        val txDescriptor = BluetoothGattDescriptor(
                mCccDescriptorUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        mTxCharacteristic.addDescriptor(txDescriptor)
        rxTxService.addCharacteristic(rxCharacteristic)
        rxTxService.addCharacteristic(mTxCharacteristic)

        mBluetoothGattServer?.addService(rxTxService)
                ?: Log.w(TAG, "Unable to create GATT server")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_NONE
            )
            foregroundNotifyManager = getSystemService(NotificationManager::class.java)
            foregroundNotifyManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createTotpRequestNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name) + "blabla"
            val descriptionText = "..."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID_TOTP_REQ, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system.
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun shortenBtAdapterName() {
        mBluetoothManager?.adapter?.name = mBluetoothManager?.adapter?.name?.replace(" ", "")?.take(8)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this

        // Notification for being at foreground:
        val input = intent!!.getStringExtra("inputExtra")
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this,
            0, notificationIntent, FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_foreground))
            .setContentText(input)
            .setSmallIcon(R.drawable.ic_launcher_foreground_whitened)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setColor(Color.argb(255,0, 130, 252))
            .build()
        startForeground(-1, notification)

        // notification for asking user to accept totp request:
        createTotpRequestNotificationChannel()
        notifyBuilder = NotificationCompat.Builder(this, CHANNEL_ID_TOTP_REQ)
            .setSmallIcon(R.drawable.ic_launcher_foreground_whitened)
            .setContentTitle(getString(R.string.notify_totp_req_title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setColor(Color.argb(255,0, 130, 252))
            .setDefaults(DEFAULT_SOUND or DEFAULT_VIBRATE)

        initBleGattServer()
        mBluetoothLeAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback)

        // When the user enables or disables Bluetooth, we want to restart/stop advertising.
        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mBleBroadcastReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(mBleBroadcastReceiver, intentFilter)
        }
        val localIntentFilter = IntentFilter(START_ADVERTISING_ACTION)
        localIntentFilter.addAction(ACTION_CONFIRM_TOTP_REQUEST)
        localIntentFilter.addAction(ACTION_DENY_TOTP_REQUEST)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBleServiceBroadcastReceiver, localIntentFilter)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        // Technically, onDestroy() will only be called when Android itself closes the service
        // (when it needs performance capabilities or memory) or stopSelf() or similar is called (
        // not the case at the moment). The other way to stop this foreground service is the user
        // stopping it via the task manager. But that doesn't call onDestroy().
        // Because of this the the client will not get informed about disconnects made by the user
        // stopping the service via the android task manager.
        job.cancel() // coroutine related
        unregisterReceiver(mBleBroadcastReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBleServiceBroadcastReceiver)

        if (isConnectedWithDevice()) {
            mBluetoothGattServer?.cancelConnection(mBleDevice)
        }
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback) // may be redundant to gattserver.close()
        mBluetoothGattServer?.close()

        Log.i(TAG, "destroyed ble service")
        super.onDestroy()
    }
}