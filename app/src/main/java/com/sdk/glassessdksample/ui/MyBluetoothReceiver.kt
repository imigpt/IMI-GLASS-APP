package com.sdk.glassessdksample.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.request.WriteRequest
import com.sdk.glassessdksample.BuildConfig
import org.greenrobot.eventbus.EventBus

class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {

    private val TAG = "MyBluetoothReceiver"
    // Use getInstance() instead of constructor since HotHelper is a Singleton
    private val hotHelper by lazy { HotHelper.getInstance(MyApplication.instance) }
    private val mainHandler = Handler(Looper.getMainLooper())
    // When true, prefer the glasses' native/firmware wake-word detection
    // and do not start the in-app Porcupine `HotHelper`.
    // Set to false to use phone-based "Hey IMI" detection via Porcupine
    // instead of the glasses' firmware "Hey Cyan" detector.
    private var enableNativeWake = false

    @SuppressLint("MissingPermission")
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.i(TAG, "Connection Status: Device=${device?.name}, Connected=$connected")
        if (device != null && connected) {
            device.name?.let { DeviceManager.getInstance().deviceName = it }
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.CONNECTED))
            // Start phone mic Porcupine to detect "Hey Imi"
            startCustomWakeWord()
        } else {
            stopCustomWakeWord()
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.DISCONNECTED))
        }
    }

    private fun startCustomWakeWord() {
        try {
            // Check microphone permission before attempting to initialize custom voice detector.
            val micGranted = ContextCompat.checkSelfPermission(
                MyApplication.instance,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!micGranted) {
                Log.w(TAG, "RECORD_AUDIO permission is not granted. Posting request event.")
                EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.REQUEST_MIC_PERMISSION))
                return
            }

            Log.i(TAG, "Starting custom voice detector for wake word detection")

            // Delay initialization slightly to allow BLE connection to settle
            mainHandler.postDelayed({
                try {
                    Log.i(TAG, "Initializing HotHelper (delayed start).")
                    hotHelper.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing or starting HotHelper: ${e.message}", e)
                }
            }, 700) // 700 ms delay

        } catch (e: Exception) {
            Log.e(TAG, "Error starting custom voice detection: ${e.message}")
        }
    }

    private fun stopCustomWakeWord() {
        hotHelper.stop()
    }

    private fun setNativeWakeWord(enable: Boolean) {
        val command = if (enable) byteArrayOf(0x44, 0x01) else byteArrayOf(0x44, 0x00)
        Log.d(TAG, "${if (enable) "Enabling" else "Disabling"} native Wake Word...")
        try {
            if (Constants.SERIAL_PORT_SERVICE != null && Constants.SERIAL_PORT_CHARACTER_WRITE != null) {
                val writeReq = WriteRequest(Constants.SERIAL_PORT_SERVICE, Constants.SERIAL_PORT_CHARACTER_WRITE)
                writeReq.value = command
                BleOperateManager.getInstance().execute(writeReq)
            } else {
                Log.e(TAG, "Cannot send command: Constants UUIDs are null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send BLE command: ${e.message}")
        }
    }

    override fun onServiceDiscovered() {
        Log.i(TAG, "BLE Services Discovered - Glass Ready")
        LargeDataHandler.getInstance().initEnable()
        BleOperateManager.getInstance().isReady = true

        // Disable firmware wake word - we use phone mic Porcupine for "Hey Imi"
        setNativeWakeWord(false)
        // Prefer glass BLE audio for wake if available (process glass audio frames)
        try {
            hotHelper.setPreferGlassBleAudio(true)
            Log.i(TAG, "✅ Prefer Glass BLE audio for wake - initializing HotHelper")
            hotHelper.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefer Glass BLE audio: ${e.message}")
        }

        EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.CONNECTED))
    }
    


    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        // Not used - wake word detected by phone mic Porcupine
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid == null || data == null) return
        val valueStr = String(data, Charsets.UTF_8)
        when (uuid.lowercase()) {
            Constants.CHAR_FIRMWARE_REVISION.toString().lowercase() -> MyApplication.instance.firmwareVersion = valueStr
            Constants.CHAR_HW_REVISION.toString().lowercase() -> MyApplication.instance.hardwareVersion = valueStr
        }
    }
}
