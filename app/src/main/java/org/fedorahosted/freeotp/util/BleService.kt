package org.fedorahosted.freeotp.util

import android.R
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
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
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fedorahosted.freeotp.ui.MainActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays
import java.util.UUID


private val TAG = "mrnBleService"

class BleService : Service() {

    private val CHANNEL_ID = "AdvertiseBleServiceChannel"
    private var mBluetoothManager: BluetoothManager? = null
    private lateinit var mBluetoothLeAdvertiser: BluetoothLeAdvertiser

    companion object { private var mBluetoothGattServer: BluetoothGattServer? = null }
    private lateinit var mTxCharacteristic: BluetoothGattCharacteristic
    private var mBleDevice: BluetoothDevice? = null
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
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: $device")
                // TODO mrn we should check if bleDevice == devicebleDevice = null
                mBluetoothLeAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            // super.onNotificationSent(device, status)
            Log.i(TAG, "sent notification")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            Log.i(TAG, "read request")
            if (characteristic.uuid == mTxCharUuid) {
                Log.i(TAG, "onChar read request, returning GATT_SUCCESS and null")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            else {
                // TODO: this if else could be unneccessary, we probably only need the "else" stuff.
                //  Because the central device will only write, or it will be notified. It will never read.
                Log.i(TAG, "onChar read request, returning GATT_FAILURE and null, " +
                        "due unknown uuid")
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            Log.i(TAG, "write request")
            // super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val strValue = value?.toString(Charsets.UTF_8) ?: ""
            Log.i(TAG, "received value = $strValue")
            if (characteristic != null && characteristic.uuid == mRxCharUuid) {
                handleIncomingMessage(strValue)
                if (responseNeeded) {
                    Log.i(TAG, "responded with GATT_SUCCESS and $strValue")
                    mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, strValue.toByteArray(Charsets.UTF_8))
                } else {
                    Log.i(TAG, "no response needed")
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
                    Log.i(TAG, "Bluetooth adapter name before: ${mBluetoothManager?.adapter?.name}")
                    mBluetoothManager?.adapter?.name = mBluetoothManager?.adapter?.name?.replace(" ", "")?.take(8)
                    Log.i(TAG, "Bluetooth adapter name: ${mBluetoothManager?.adapter?.name}")
                    // TODO mrn maybe restart service here, restarting advertising ends in a recursive IDE error
                }
                2 -> errorMsg = "Failed to start advertising because no advertising instance is available."
                3 -> errorMsg = "Failed to start advertising as the advertising is already started"
                4 -> errorMsg = "Operation failed due to an internal error."
                5 -> errorMsg = "This feature is not supported on this platform."
            }
            Log.w(TAG, "LE Advertise Failed (name: ${mBluetoothManager?.adapter?.name}): $errorMsg")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBle(message: String) {
        mTxCharacteristic.let{
            it.value = message.toByteArray(Charsets.UTF_8)
            Log.i(TAG, "sending notify: $message")
            mBluetoothGattServer?.notifyCharacteristicChanged(mBleDevice, it, true)
        }
    }

    private fun getTotp(domain: String, username: String): String {
        // from TokenPersistence.kt:
//        val gson: Gson = Gson()
//        val savedTokens = gson.fromJson(jsonString, SavedTokens::class.java)
//        or (token in savedTokens.tokens) {
//            save(token)
//        }
//        val prefs: SharedPreferences = this.applicationContext.getSharedPreferences("tokens", Context.MODE_PRIVATE)
//        val str = prefs.getString(id, null)
//        Token(str, true)
//
//        var mCodes: TokenCode? = null
//        val code = mCodes?.currentCode?: run {
//            mCode.text = mPlaceholder
//            mProgressInner.visibility = View.GONE
//            mProgressOuter.visibility = View.GONE
//            animate(mImage, R.anim.token_image_fadein, true)
//            return
//        }
        return 420609.toString()
    }

    private fun handleIncomingMessage(message: String) {
        Log.i(TAG, "handling message: $message\nType of message: " + message::class.simpleName)
        val msg: Map<String, *>
        try {
            msg = JSONObject(message).toMap()
            Log.i(TAG, "parsed: $msg")
            when (msg["key"]) {
                "request_totp" -> {
                    val totp = getTotp(msg["domain"].toString(), msg["username"].toString())
                    sendBle(
                        mapOf(
                            "key" to "totp",
                            "totp" to totp
                        ).toString()
                                .replace("=", "\": \"")
                                .replace("{", "{\"")
                                .replace(", ", "\", \"")
                                .replace("}", "\"}")
                    )
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, e.stackTraceToString())
        }

    }

    private fun JSONObject.toMap(): Map<String, *> = keys().asSequence().associateWith {
        when (val value = this[it])
        {
            is JSONArray ->
            {
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
        if ((mBluetoothManager?.getConnectionState(mBleDevice, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED)) {
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
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent!!.getStringExtra("inputExtra")
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText(input)
                .setSmallIcon(R.drawable.arrow_up_float)
                .setContentIntent(pendingIntent)
                .build()

        startForeground(1, notification)

        initBleGattServer()
        mBluetoothLeAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseCallback)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback) // may be redundant to gattserver.close()
        mBluetoothGattServer?.close()
        Log.i(TAG, "destroyed ble service")
    }
}