package com.sdk.glassessdksample.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import org.greenrobot.eventbus.EventBus

class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {

            // ---------------------------------------------
            // BLUETOOTH ON / OFF
            // ---------------------------------------------
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)

                when (state) {

                    BluetoothAdapter.STATE_OFF -> {
                        Log.i("BluetoothReceiver", "Bluetooth turned OFF")

                        // Force disconnect BLE
                        BleOperateManager.getInstance().setBluetoothTurnOff(false)
                        BleOperateManager.getInstance().disconnect()

                        // Notify system
                        EventBus.getDefault().post(
                            BluetoothEvent(BluetoothEvent.EventType.DISCONNECTED)
                        )
                    }

                    BluetoothAdapter.STATE_ON -> {
                        Log.i("BluetoothReceiver", "Bluetooth turned ON")

                        BleOperateManager.getInstance().setBluetoothTurnOff(true)

                        // Auto reconnect to previously bound device
                        val mac = DeviceManager.getInstance().deviceAddress
                        if (!mac.isNullOrEmpty()) {
                            BleOperateManager.getInstance().reConnectMac = mac
                            BleOperateManager.getInstance().connectDirectly(mac)
                        }

                        EventBus.getDefault().post(
                            BluetoothEvent(BluetoothEvent.EventType.CONNECTED)
                        )
                    }
                }
            }

            // ---------------------------------------------
            // BOND STATE CHANGED (Pairing)
            // ---------------------------------------------
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                // Optional: triggers when pairing succeeds or fails
            }

            // ---------------------------------------------
            // ACL CONNECTED (Classic BT connected)
            // ---------------------------------------------
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                EventBus.getDefault().post(
                    BluetoothEvent(BluetoothEvent.EventType.CONNECTED)
                )
            }

            // ---------------------------------------------
            // ACL DISCONNECTED (Classic BT disconnected)
            // ---------------------------------------------
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                EventBus.getDefault().post(
                    BluetoothEvent(BluetoothEvent.EventType.DISCONNECTED)
                )
            }

            // ---------------------------------------------
            // DEVICE DISCOVERED
            // ---------------------------------------------
            BluetoothDevice.ACTION_FOUND -> {
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                if (device != null) {
                    // If discovered device matches Smart Glass → attempt bond/pair
                    BleOperateManager.getInstance().createBondBluetoothJieLi(device)
                }
            }
        }
    }
}
