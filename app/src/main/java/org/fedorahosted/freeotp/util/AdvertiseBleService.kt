package org.fedorahosted.freeotp.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID

class AdvertiseBleService : Service() {

    private val serviceUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f852")
    private val txCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f853")
    private val rxCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f854")
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothGattServer: BluetoothGattServer? = null

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("mrndebug", "BluetoothDevice CONNECTED: $device")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("mrndebug", "BluetoothDevice DISCONNECTED: $device")
                // Remove device from any active subscriptions
                // registeredDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic) {
            bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    "ack".toByteArray())
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("mrndebug", "LE Advertise Started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w("mrndebug", "LE Advertise Failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("mrndebug", "starting BLE Service")

        bluetoothManager =  getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
                bluetoothManager.adapter.bluetoothLeAdvertiser
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val rxTxService = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rxCharacteristic = BluetoothGattCharacteristic(
            rxCharUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        val txCharacteristic = BluetoothGattCharacteristic(
            txCharUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)

        rxTxService.addCharacteristic(rxCharacteristic)
        rxTxService.addCharacteristic(txCharacteristic)

        bluetoothGattServer?.addService(rxTxService)
                ?: Log.w("mrndebug", "Unable to create GATT server")

        bluetoothLeAdvertiser?.let {
            val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .build()

            val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(serviceUuid))
                    .build()

            it.startAdvertising(settings, data, advertiseCallback)
        } ?: Log.w("mrndebug", "Failed to create advertiser")

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}