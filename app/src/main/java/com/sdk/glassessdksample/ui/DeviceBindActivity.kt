package com.sdk.glassessdksample.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivityDeviceBindBinding
import com.xiasuhuei321.loadingdialog.view.LoadingDialog
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {

    private lateinit var binding: ActivityDeviceBindBinding
    private lateinit var adapter: DeviceListAdapter
    private val deviceList = mutableListOf<SmartWatch>()

    private var scanSize = 0
    private var loadingDialog: LoadingDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanStopRunnable = Runnable {
        BleScannerHelper.getInstance().stopScan(this)
        binding.startScan.text = "Start Scan"
    }

    private val bleScanCallback = BleCallback()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Views should be setup after permissions are granted
    }

    override fun setupViews() {

        adapter = DeviceListAdapter(this, deviceList)
        binding.deviceRcv.layoutManager = LinearLayoutManager(this)
        binding.deviceRcv.adapter = adapter

        binding.titleBar.tvTitle.text = "Scan Smart Glass"
        binding.titleBar.ivNavigateBefore.setOnClickListener { finish() }

        adapter.setOnItemClickListener { _, _, position ->
            mainHandler.removeCallbacks(scanStopRunnable)

            val device = deviceList[position]
            BleOperateManager.getInstance().connectDirectly(device.deviceAddress)

            loadingDialog = LoadingDialog(this)
            loadingDialog?.setLoadingText(getString(R.string.text_22))?.show()
        }

        binding.startScan.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions()
                return@setOnClickListener
            }

            startScanning()
        }
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(scanStopRunnable)
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: BluetoothEvent) {

        when (event.type) {

            BluetoothEvent.EventType.CONNECTED -> {
                loadingDialog?.close()

                // ========== OPTION A: Check for audio connection after BLE connects ==========
                Log.d("DeviceBindActivity", "✅ BLE Connected - Checking audio connection...")

                Toast.makeText(this, "✅ Glass Connected (Data)", Toast.LENGTH_SHORT).show()

                // Check if audio is also connected shortly after BLE connects
                Handler(Looper.getMainLooper()).postDelayed({
                    checkAudioConnectionAndPrompt()
                }, 1500)

                finish()
            }

            BluetoothEvent.EventType.VOICE_TEXT -> {
                val text = (event.data as? String)?.lowercase() ?: ""

                if (text.contains("wake up") || text.contains("hey imi")) {
                    playNotificationSound()
                    Toast.makeText(this, "Wake word detected!", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    private fun playNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ring = RingtoneManager.getRingtone(applicationContext, uri)
            ring.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if audio connection exists and prompt if missing
     */
    private fun checkAudioConnectionAndPrompt() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val hasAudioConnection = audioManager?.isBluetoothScoAvailableOffCall == true

        if (!hasAudioConnection) {
            // Audio not connected - show setup guide
            showAudioConnectionPrompt()
        } else {
            // All good!
            Toast.makeText(this, "🎧 Glass mic ready!", Toast.LENGTH_SHORT).show()
            Log.d("DeviceBindActivity", "✅ Both BLE and Audio connections active")
        }
    }

    /**
     * Prompt user to setup audio connection
     */
    private fun showAudioConnectionPrompt() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🎤 One More Step!")
            .setMessage(
                "Your glasses are connected for data ✅\n\n" +
                "To use the glass microphone:\n\n" +
                "1️⃣ Go to Bluetooth Settings\n" +
                "2️⃣ Tap your glasses name\n" +
                "3️⃣ Enable 'Phone Audio' ✅\n\n" +
                "This enables the microphone & speaker.\n" +
                "Skip if you want to use phone mic only."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openBluetoothSettingsAndGuide()
            }
            .setNegativeButton("Later") { dialog, _ ->
                Toast.makeText(
                    this,
                    "You can enable glass mic anytime from Bluetooth settings",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNeutralButton("Help") { _, _ ->
                showDetailedSetupHelp()
            }
            .create()

        dialog.show()
    }

    /**
     * Open Bluetooth settings with guidance
     */
    private fun openBluetoothSettingsAndGuide() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)

            // Show step-by-step toast after opening settings
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(
                    this,
                    "1️⃣ Find your glasses\n2️⃣ Tap ⚙️ settings\n3️⃣ Enable 'Phone Audio'",
                    Toast.LENGTH_LONG
                ).show()
            }, 1000)

        } catch (e: Exception) {
            Log.e("DeviceBindActivity", "Failed to open Bluetooth settings: ${e.message}")
            Toast.makeText(
                this,
                "Please manually go to: Settings → Bluetooth",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Show detailed setup help
     */
    private fun showDetailedSetupHelp() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("📖 Setup Guide")
            .setMessage(
                "Why do I need this?\n\n" +
                "Your glasses use TWO Bluetooth connections:\n\n" +
                "📡 BLE (Low Energy):\n" +
                "   ✅ Already connected!\n" +
                "   • Photos, commands, data\n\n" +
                "🎧 Classic Bluetooth:\n" +
                "   ⚠️ Needs setup\n" +
                "   • Microphone & speaker\n" +
                "   • Called 'Phone Audio'\n\n" +
                "How to enable Phone Audio:\n" +
                "1. Settings → Bluetooth\n" +
                "2. Find your glasses\n" +
                "3. Tap the ⚙️ icon\n" +
                "4. Toggle 'Phone Audio' ON\n\n" +
                "That's it! Your glass mic will work."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openBluetoothSettingsAndGuide()
            }
            .setNegativeButton("Got It") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()
    }

    private fun requestPermissions() {

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        XXPermissions.with(this)
            .permission(permissions)
            .request(object : OnPermissionCallback {
                override fun onGranted(list: MutableList<String>, all: Boolean) {
                    if (all) {
                        setupViews()
                        startScanning()
                    }
                }

                override fun onDenied(list: MutableList<String>, never: Boolean) {
                    if (never) {
                        XXPermissions.startPermissionActivity(this@DeviceBindActivity, list)
                    }
                }
            })
    }

    private fun startScanning() {

        if (!isBluetoothEnabled()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        deviceList.clear()
        adapter.notifyDataSetChanged()

        scanSize = 0

        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)

        binding.startScan.text = "Stop Scan"

        mainHandler.removeCallbacks(scanStopRunnable)
        mainHandler.postDelayed(scanStopRunnable, 15000)
    }
    
    private fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled ?: false
    }

    inner class BleCallback : ScanWrapperCallback {

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device == null || device.name.isNullOrEmpty()) return

            val newDevice = SmartWatch(device.name, device.address, rssi = rssi)

            if (deviceList.any { it.deviceAddress == newDevice.deviceAddress }) return

            deviceList.add(newDevice)
            deviceList.sortByDescending { it.rssi }
            adapter.notifyDataSetChanged()

            scanSize++
            if (scanSize > 30) {
                BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
            }
        }

        override fun onStart() {}
        override fun onStop() {}
        override fun onScanFailed(errorCode: Int) {}
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(scanStopRunnable)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }
}
