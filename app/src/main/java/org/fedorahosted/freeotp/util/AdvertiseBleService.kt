package org.fedorahosted.freeotp.util

import android.annotation.SuppressLint
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
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.util.Arrays
import java.util.UUID

class AdvertiseBleService : Service() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser

    private var bluetoothGattServer: BluetoothGattServer? = null
    private val serviceUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f852")
    private val txCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f853")
    // This descriptor uuid is given by the BLE spec. This tx characteristic may notify the central device, therefore we need this descriptor:
    private val cccDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val rxCharUuid: UUID = UUID.fromString("2e076308-26cb-4a9c-a79a-e3ec22b3f854")
    val advertiseSettings: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
    val advertiseData: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("mrndebug", "BluetoothDevice CONNECTED: $device")
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("mrndebug", "BluetoothDevice DISCONNECTED: $device")
                bluetoothLeAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            // super.onNotificationSent(device, status)
            Log.i("mrndebug", "sent notification")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            Log.i("mrndebug", "read request")
            if (characteristic.uuid == txCharUuid) {
                Log.i("mrndebug", "onChar read request, returning GATT_SUCCESS and null")
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            else {
                // TODO: this if else could be unneccessary, we probably only need the "else" stuff.
                //  Because the central device will only write, or it will be notified. It will never read.
                Log.i("mrndebug", "onChar read request, returning GATT_FAILURE and null, " +
                        "due unknown uuid")
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            Log.i("mrndebug", "write request")
            // super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val strValue = value?.toString(Charsets.UTF_8) ?: ""
            Log.i("mrndebug", "received value = $strValue")
            if (characteristic != null && characteristic.uuid == rxCharUuid) {
                if (responseNeeded) {
                    Log.i("mrndebug", "responded with GATT_SUCCESS and $strValue")
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, strValue.toByteArray(Charsets.UTF_8))
                } else {
                    Log.i("mrndebug", "no response needed")
                }
            } else {
                if (responseNeeded) {
                    Log.i("mrndebug", "responded with GATT_FAILURE")
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                } else {
                    Log.i("mrndebug", "no response needed")
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            Log.i("mrndebug", "descriptor read request")
            // super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            if (descriptor != null && descriptor.uuid == cccDescriptorUuid) {
                Log.i("mrndebug", "responding with GATT_SUCCESS and ENABLE_NOTIFICATION_VALUE")
                val ret = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ret)
            } else {
                Log.i("mrndebug", "responding with GATT_FAILURE, due unknown uuid")
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
//            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            Log.i("mrndebug", "descriptor write request")
            if (descriptor != null && descriptor.uuid == cccDescriptorUuid) {
                var status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                if (descriptor.characteristic.uuid == txCharUuid) {
                    if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        status = BluetoothGatt.GATT_SUCCESS
                    } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        status = BluetoothGatt.GATT_SUCCESS
                    }
                }
                if (responseNeeded) {
                    Log.i("mrndebug", "responding with $status")
                    bluetoothGattServer?.sendResponse(device, requestId, status, 0, null)
                }
            } else {
                Log.i("mrndebug", "responding with GATT_FAILURE due unknown uuid")
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
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
        bluetoothLeAdvertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        bluetoothManager.adapter.setName(bluetoothManager.adapter.name.take(8))

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
        val txDescriptor = BluetoothGattDescriptor(
                cccDescriptorUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        txCharacteristic.addDescriptor(txDescriptor)
        rxTxService.addCharacteristic(rxCharacteristic)
        rxTxService.addCharacteristic(txCharacteristic)

        bluetoothGattServer?.addService(rxTxService)
                ?: Log.w("mrndebug", "Unable to create GATT server")
        bluetoothLeAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("mrndebug", "destroyed ble service")
        this.stopSelf()
    }
}